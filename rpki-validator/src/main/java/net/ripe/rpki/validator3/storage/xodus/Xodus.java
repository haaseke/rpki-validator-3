/**
 * The BSD License
 *
 * Copyright (c) 2010-2018 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator3.storage.xodus;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import jetbrains.exodus.ArrayByteIterable;
import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.env.Cursor;
import jetbrains.exodus.env.Environment;
import jetbrains.exodus.env.EnvironmentStatistics;
import jetbrains.exodus.env.Store;
import jetbrains.exodus.env.StoreConfig;
import jetbrains.exodus.env.Transaction;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.validator3.storage.Bytes;
import net.ripe.rpki.validator3.storage.data.Key;
import net.ripe.rpki.validator3.storage.encoding.Coder;
import net.ripe.rpki.validator3.storage.encoding.CoderFactory;
import org.apache.commons.lang3.tuple.Pair;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static jetbrains.exodus.env.StoreConfig.USE_EXISTING;
import static jetbrains.exodus.env.StoreConfig.WITHOUT_DUPLICATES;

@Slf4j
public abstract class Xodus {

    private static final String METADATA_MAP_NAME = "meta";
    private Gson gson = new Gson();
    private Store metadata;

    protected synchronized Store meta(){
        if(metadata == null){
            metadata = getEnv().computeInTransaction(txn ->
                    getEnv().openStore(METADATA_MAP_NAME, WITHOUT_DUPLICATES, txn));
        }
        return metadata;
    }

    protected abstract Environment getEnv();

    public <T> T writeTx(Function<XodusTx.Write, T> f) {
        XodusTx.Write tx = XodusTx.write(getEnv());
        txs.put(tx.getId(), new Xodus.TxInfo(tx));
        try {
            final T result = f.apply(tx);
            tx.txn().commit();
            if (tx.getAfterCommit() != null) {
                tx.getAfterCommit().forEach(r -> {
                    try {
                        r.run();
                    } catch (Exception ignored) {
                        // this is just to keep the loop going, every Runnable
                        // has to take care of exceptions themselves
                    }
                });
            }
            return result;
        } finally {
            tx.close();
            txs.remove(tx.getId());
        }
    }

    public void writeTx0(Consumer<XodusTx.Write> c) {
        writeTx(tx -> {
            c.accept(tx);
            return null;
        });
    }

    public <T> T readTx(Function<XodusTx.Read, T> f) {
        XodusTx.Read tx = XodusTx.read(getEnv());
        txs.put(tx.getId(), new TxInfo(tx));
        try {
            return f.apply(tx);
        } finally {
            tx.close();
            txs.remove(tx.getId());
        }
    }

    public void readTx0(Consumer<XodusTx.Read> c) {
        readTx(tx -> {
            c.accept(tx);
            return null;
        });
    }

    static void checkEnv(Environment env) {
        if (!env.isOpen()) {
            throw new XodusClosedException();
        }
    }
    public String status() {
        EnvironmentStatistics statistics = (EnvironmentStatistics)getEnv().getStatistics();
        return Arrays.stream(EnvironmentStatistics.Type.values())
                .map(k -> k.name() + ":" + statistics.getStatisticsItem(k).getTotal())
                .collect(Collectors.joining(","));

    }

    @Getter
    private final Map<Long, TxInfo> txs = new ConcurrentHashMap<>();

    private final Map<String, IxBaseX<?>> ixMaps = new ConcurrentHashMap<>();

    public <T extends Serializable> IxMapX<T> createIxMap(String name,
                                                         Map<String, Function<T, Set<Key>>> indexFunctions,
                                                         Class<T> c) {
        return createIxMap(name, indexFunctions, CoderFactory.makeCoder(c));
    }

    public <T extends Serializable> IxMapX<T> createIxMap(final String name,
                                                         final Map<String, Function<T, Set<Key>>> indexFunctions,
                                                         Coder<T> c) {
        IxMapX<T> ixMap = new IxMapX<>(this, name, c, indexFunctions);
        ixMaps.put(name, ixMap);
        return ixMap;
    }

    Store createMainMapDb(String name) {
        final String dbName = name + "-main";
        final Store store = getEnv().computeInTransaction(txn ->
                getEnv().openStore(dbName, WITHOUT_DUPLICATES, txn));

        // don't go into infinite recursion when creating IxMap for meta and do it manually
        final IxMapInfo mapInfo = new IxMapInfo();
        mapInfo.setName(dbName);
        saveDbMeta(mapInfo);
        return store;
    }

    private void saveDbMeta(IxMapInfo mapInfo) {
        final Key key = dbMetaKey(mapInfo.getName());
        final ByteIterable foo = new ArrayByteIterable(gson.toJson(mapInfo).getBytes(UTF_8));
        getEnv().executeInTransaction(tx -> meta().put(tx, key.toByteIterable(), foo));
    }

    private Key dbMetaKey(String dbName) {
        return Key.of(dbName + "-key");
    }

    <T extends Serializable> Pair<Map<String, Store>, Boolean> createIndexes(
            String name,
            Map<String, Function<T, Set<Key>>> indexFunctions,
            StoreConfig storeConfigs) {
        final Store meta = meta();
        Xodus.IxMapInfo existingIxMapInfo = readTx(tx -> {
            ByteBuffer bb = iterableToByteBuffer(meta.get(tx.txn(), dbMetaKey(name).toByteIterable()));
            if (bb == null) {
                return null;
            }
            String json = new String(Bytes.toBytes(bb), UTF_8);
            return gson.fromJson(json, Xodus.IxMapInfo.class);
        });

        final Map<String, Store> indexes = new HashMap<>();
        final Environment env = getEnv();
        boolean reindex = false;
        if (existingIxMapInfo != null) {
            final Set<String> existingIndexes = existingIxMapInfo.getIndexes();
            if (existingIndexes != null) {
                if (!existingIndexes.equals(indexFunctions.keySet())) {
                    Sets.difference(existingIndexes, indexFunctions.keySet()).forEach(idx -> {
                        getEnv().executeInTransaction(txn -> {
                                    Store store = getEnv().openStore(indexDbName(name, idx), USE_EXISTING, txn);
                                    clearStore(txn, store);
                                }
                        );
                    });
                    existingIxMapInfo.setIndexes(indexFunctions.keySet());
                    saveDbMeta(existingIxMapInfo);
                    reindex = true;
                }
            } else {
                existingIxMapInfo.setIndexes(indexFunctions.keySet());
                saveDbMeta(existingIxMapInfo);
            }
        } else {
            Xodus.IxMapInfo mapInfo = new Xodus.IxMapInfo();
            mapInfo.setName(name);
            mapInfo.setIndexes(indexFunctions.keySet());
            saveDbMeta(mapInfo);
        }
        getEnv().executeInTransaction(txn -> {
            indexFunctions.forEach((n, idxFun) -> {
                Store store = getEnv().openStore(indexDbName(name, n), storeConfigs, txn);
                indexes.put(n, store);
            });
        });

        return Pair.of(indexes, reindex);
    }

    private String indexDbName(String name, String idx) {
        return name + "-idx-" + idx;
    }
    @Data
    private static class IxMapInfo {
        private String name;
        private Set<String> indexes;
    }

    @Data
    public static class TxInfo {
        private Long txId;
        private Long threadId;
        private List<String> stackTrace;
        private boolean writing;
        private Instant startedAt;

        TxInfo(XodusTx xodusTx) {
            this.txId = xodusTx.getId();
            this.threadId = xodusTx.getThreadId();
            this.stackTrace = Stream.of(Thread.currentThread().getStackTrace())
                    .skip(2)
                    .map(StackTraceElement::toString)
                    .collect(Collectors.toList());
            this.writing = xodusTx instanceof XodusTx.Write;
            this.startedAt = Instant.now();
        }
    }

    public static ByteBuffer iterableToByteBuffer(ByteIterable bi) {
        return ByteBuffer.wrap(bi.getBytesUnsafe());
    }

    public static ByteIterable byteBufferToIterable(ByteBuffer bb){
        return new ArrayByteIterable(bb.array());
    }

    //DBI had a drop method to clear store, Xodus store does not have, unless there is a better way to do this for
    // now we iterate and delete
    public static void clearStore(Transaction txn, Store s){
        Cursor cursor = s.openCursor(txn);
        s.delete(txn, cursor.getKey());
        while(cursor.getNext()){
            s.delete(txn, cursor.getKey());
        }
    }
}

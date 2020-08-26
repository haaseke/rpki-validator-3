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
package net.ripe.rpki.validator3.domain.validation;

import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.validator3.storage.data.TrustAnchor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * It is to keep track of the state of a TA. A TA transitions to the VALIDATED when
 * and only when the data for it has been downloaded and the tree validation has been
 * performed.
 */
@Component
@Slf4j
public class TrustAnchorState {

    private enum State {
        UNKNOWN,
        VALIDATED
    }

    private final Map<String, State> states = new HashMap<>();

    public boolean allTAsValidatedAfterRepositoryLoading() {
        synchronized (states) {
            return states.values().stream().allMatch(s -> s.equals(State.VALIDATED));
        }
    }

    public void setUnknown(TrustAnchor ta) {
        setState(ta, State.UNKNOWN);
    }

    public void setValidatedAfterLastRepositoryUpdate(TrustAnchor ta) {
        setState(ta, State.VALIDATED);
    }

    private void setState(TrustAnchor ta, State state) {
        synchronized (states) {
            State previousState = states.put(ta.getName(), state);
            if (state != previousState) {
                log.debug("Setting TA {} to {}", ta.getName(), state);
            }
        }
    }

}

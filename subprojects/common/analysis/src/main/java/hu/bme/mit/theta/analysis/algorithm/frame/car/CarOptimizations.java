/*
 *  Copyright 2026 Budapest University of Technology and Economics
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package hu.bme.mit.theta.analysis.algorithm.frame.car;

import hu.bme.mit.theta.analysis.algorithm.frame.base.BaseOptimizations;

public class CarOptimizations extends BaseOptimizations {

    private final boolean coverOpt;
    private final boolean storeFrames;
    private final boolean storeNodes;
    private final boolean refreshFrameProp;
    private final boolean deepestFirst;
    private final boolean resetFrameProp;
    private final boolean resetFrameNumber;
    private final boolean propagationCache;

    public CarOptimizations(
            boolean unSatOpt,
            boolean notBOpt,
            boolean propagateOpt,
            boolean propertyOpt,
            boolean filterOpt,
            boolean generalizeOpt,
            boolean unsatPropagateOpt,
            boolean coverOpt,
            boolean monotonoousFrames,
            boolean storeFrames,
            boolean storeNodes) {
        this(
                unSatOpt,
                notBOpt,
                propagateOpt,
                propertyOpt,
                filterOpt,
                generalizeOpt,
                unsatPropagateOpt,
                coverOpt,
                monotonoousFrames,
                storeFrames,
                storeNodes,
                false);
    }

    public CarOptimizations(
            boolean unSatOpt,
            boolean notBOpt,
            boolean propagateOpt,
            boolean propertyOpt,
            boolean filterOpt,
            boolean generalizeOpt,
            boolean unsatPropagateOpt,
            boolean coverOpt,
            boolean monotonoousFrames,
            boolean storeFrames,
            boolean storeNodes,
            boolean refreshFrameProp) {
        this(
                unSatOpt,
                notBOpt,
                propagateOpt,
                propertyOpt,
                filterOpt,
                generalizeOpt,
                unsatPropagateOpt,
                coverOpt,
                monotonoousFrames,
                storeFrames,
                storeNodes,
                refreshFrameProp,
                false);
    }

    public CarOptimizations(
            boolean unSatOpt,
            boolean notBOpt,
            boolean propagateOpt,
            boolean propertyOpt,
            boolean filterOpt,
            boolean generalizeOpt,
            boolean unsatPropagateOpt,
            boolean coverOpt,
            boolean monotonoousFrames,
            boolean storeFrames,
            boolean storeNodes,
            boolean refreshFrameProp,
            boolean deepestFirst) {
        this(
                unSatOpt,
                notBOpt,
                propagateOpt,
                propertyOpt,
                filterOpt,
                generalizeOpt,
                unsatPropagateOpt,
                coverOpt,
                monotonoousFrames,
                storeFrames,
                storeNodes,
                refreshFrameProp,
                deepestFirst,
                false);
    }

    public CarOptimizations(
            boolean unSatOpt,
            boolean notBOpt,
            boolean propagateOpt,
            boolean propertyOpt,
            boolean filterOpt,
            boolean generalizeOpt,
            boolean unsatPropagateOpt,
            boolean coverOpt,
            boolean monotonoousFrames,
            boolean storeFrames,
            boolean storeNodes,
            boolean refreshFrameProp,
            boolean deepestFirst,
            boolean resetFrameProp) {
        this(
                unSatOpt,
                notBOpt,
                propagateOpt,
                propertyOpt,
                filterOpt,
                generalizeOpt,
                unsatPropagateOpt,
                coverOpt,
                monotonoousFrames,
                storeFrames,
                storeNodes,
                refreshFrameProp,
                deepestFirst,
                resetFrameProp,
                false);
    }

    public CarOptimizations(
            boolean unSatOpt,
            boolean notBOpt,
            boolean propagateOpt,
            boolean propertyOpt,
            boolean filterOpt,
            boolean generalizeOpt,
            boolean unsatPropagateOpt,
            boolean coverOpt,
            boolean monotonoousFrames,
            boolean storeFrames,
            boolean storeNodes,
            boolean refreshFrameProp,
            boolean deepestFirst,
            boolean resetFrameProp,
            boolean resetFrameNumber) {
        this(
                unSatOpt,
                notBOpt,
                propagateOpt,
                propertyOpt,
                filterOpt,
                generalizeOpt,
                unsatPropagateOpt,
                coverOpt,
                monotonoousFrames,
                storeFrames,
                storeNodes,
                refreshFrameProp,
                deepestFirst,
                resetFrameProp,
                resetFrameNumber,
                false);
    }

    public CarOptimizations(
            boolean unSatOpt,
            boolean notBOpt,
            boolean propagateOpt,
            boolean propertyOpt,
            boolean filterOpt,
            boolean generalizeOpt,
            boolean unsatPropagateOpt,
            boolean coverOpt,
            boolean monotonoousFrames,
            boolean storeFrames,
            boolean storeNodes,
            boolean refreshFrameProp,
            boolean deepestFirst,
            boolean resetFrameProp,
            boolean resetFrameNumber,
            boolean propagationCache) {
        super(
                unSatOpt,
                notBOpt,
                propagateOpt,
                filterOpt,
                propertyOpt,
                generalizeOpt,
                unsatPropagateOpt,
            monotonoousFrames);
        if (resetFrameProp && !resetFrameNumber) {
            throw new IllegalArgumentException(
                    "resetFrameProp requires resetFrameNumber: a frame's property can only be"
                            + " reset if the frame number is reset too");
        }
        this.coverOpt = coverOpt;
        this.storeFrames = storeFrames;
        this.storeNodes = storeNodes;
        this.refreshFrameProp = refreshFrameProp;
        this.deepestFirst = deepestFirst;
        this.resetFrameProp = resetFrameProp;
        this.resetFrameNumber = resetFrameNumber;
        this.propagationCache = propagationCache;
    }

    public boolean isCoverOpt() {
        return coverOpt;
    }

    /**
     * Whether accumulated frames (overapproximation invariants) are kept across CARCEGAR
     * iterations. When {@code false}, frames are discarded and rebuilt from scratch at the start
     * of every CEGAR iteration.
     */
    public boolean isStoreFrames() {
        return storeFrames;
    }

    /**
     * Whether the explored counterexample/proof-obligation node tree is kept across CARCEGAR
     * iterations. When {@code false}, the node tree is discarded and rebuilt from scratch at the
     * start of every CEGAR iteration.
     */
    public boolean isStoreNodes() {
        return storeNodes;
    }

    /**
     * Whether the fixpoint check compares frames kept across a CARCEGAR refinement (see {@link
     * #isStoreFrames()}) under the refined model's property. When {@code false}, each frame is
     * compared under the property it was built with, and old frames never compare equal to new
     * ones. Other queries keep each frame's own property either way: with the transition relation
     * the two are equivalent, and swapping them only changes which models the solver returns.
     */
    public boolean isRefreshFrameProp() {
        return refreshFrameProp;
    }

    /**
     * Whether the main loop explores the unchecked node furthest from the root first (ties in
     * insertion order). When {@code false}, it explores the unchecked nodes in insertion order.
     */
    public boolean isDeepestFirst() {
        return deepestFirst;
    }

    /**
     * Whether every frame kept across a CARCEGAR refinement (see {@link #isStoreFrames()}) replaces
     * the property it was built with by the refined model's property whenever the refined model is
     * set. Unlike {@link #isRefreshFrameProp()}, this affects every query of the frame, not only
     * the fixpoint check. Requires {@link #isResetFrameNumber()}.
     */
    public boolean isResetFrameProp() {
        return resetFrameProp;
    }

    /**
     * Whether the current frame number restarts from 0 at the start of every CARCEGAR iteration
     * after the first. Frames kept across the refinement (see {@link #isStoreFrames()}) are not
     * discarded: the search climbs back through them, reusing their clauses. When {@code false},
     * the search continues from the frame the previous iteration stopped at.
     */
    public boolean isResetFrameNumber() {
        return resetFrameNumber;
    }

    /**
     * Whether forward propagation skips a clause whose propagation from the same frame was already
     * tried while neither that frame nor the model changed (the query would give the same result),
     * and, without {@link #isUnsatPropagateOpt()}, a clause the next frame already implies.
     */
    public boolean isPropagationCache() {
        return propagationCache;
    }
}

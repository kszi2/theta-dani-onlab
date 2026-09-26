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
        super(
                unSatOpt,
                notBOpt,
                propagateOpt,
                filterOpt,
                propertyOpt,
                generalizeOpt,
                unsatPropagateOpt,
            monotonoousFrames);
        this.coverOpt = coverOpt;
        this.storeFrames = storeFrames;
        this.storeNodes = storeNodes;
        this.refreshFrameProp = refreshFrameProp;
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
}

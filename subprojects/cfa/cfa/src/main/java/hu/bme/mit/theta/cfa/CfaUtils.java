/*
 *  Copyright 2025 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.cfa;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public final class CfaUtils {

    private CfaUtils() {}

    /**
     * Create a new CFA containing only the given locations and the edges between them.
     *
     * @param original The original CFA
     * @param selectedLocs Subset of locations to keep
     * @param initLocInNewCfa The location that should serve as init in the new CFA
     *                        (must be one of the selected locations)
     * @return A new CFA containing only the selected locations
     */
    public static CFA restrictToLocations(
            final CFA original,
            final Collection<CFA.Loc> selectedLocs,
            final CFA.Loc initLocInNewCfa,
            final CFA.Loc errorLocInNewCfa,
            final CFA.Loc finalLocInNewCfa) {

        CFA.Builder builder = CFA.builder();

        // Map old Loc -> new Loc
        Map<CFA.Loc, CFA.Loc> locMap = new HashMap<>();

        // Recreate the selected locations
        for (CFA.Loc oldLoc : selectedLocs) {
            CFA.Loc newLoc = builder.createLoc(oldLoc.getName());
            locMap.put(oldLoc, newLoc);
        }

        // Recreate edges where both endpoints are kept
        for (CFA.Edge oldEdge : original.getEdges()) {
            if (locMap.containsKey(oldEdge.getSource()) && locMap.containsKey(oldEdge.getTarget()) && !oldEdge.getSource().equals(errorLocInNewCfa)) {
                CFA.Edge newEdge =
                        builder.createEdge(
                                locMap.get(oldEdge.getSource()),
                                locMap.get(oldEdge.getTarget()),
                                oldEdge.getStmt());
                if (original.getAcceptingEdges().contains(oldEdge)) {
                    builder.addAcceptingEdge(newEdge);
                }
            }
        }

        // Set the init location (must be included in subset)
        if (!locMap.containsKey(initLocInNewCfa)) {
            throw new IllegalArgumentException(
                    "The chosen init location is not in the selected location set.");
        }
        builder.setInitLoc(locMap.get(initLocInNewCfa));

        // Preserve final/error if they are in the subset
        if (original.getFinalLoc().isPresent() && locMap.containsKey(original.getFinalLoc().get())) {
            builder.setFinalLoc(locMap.get(original.getFinalLoc().get()));
        }

        if (original.getErrorLoc().isPresent() && locMap.containsKey(original.getErrorLoc().get())) {
            builder.setErrorLoc(locMap.get(original.getErrorLoc().get()));
        }

        return builder.build();
    }
}
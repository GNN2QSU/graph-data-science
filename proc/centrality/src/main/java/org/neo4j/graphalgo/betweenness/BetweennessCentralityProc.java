/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.betweenness;

import org.neo4j.graphalgo.AlgoBaseProc;
import org.neo4j.graphalgo.AlgorithmFactory;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.concurrency.Pools;
import org.neo4j.graphalgo.core.utils.mem.MemoryEstimation;
import org.neo4j.graphalgo.core.utils.mem.MemoryEstimations;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.write.PropertyTranslator;
import org.neo4j.graphalgo.result.AbstractResultBuilder;
import org.neo4j.logging.Log;

final class BetweennessCentralityProc {

    static final String BETWEENNESS_DESCRIPTION = "Betweenness centrality measures the relative information flow that goes through a node.";

    private BetweennessCentralityProc() {}

    static <CONFIG extends BetweennessCentralityBaseConfig> AlgorithmFactory<BetweennessCentrality, CONFIG> algorithmFactory(CONFIG config) {
        return new AlgorithmFactory<>() {
            @Override
            public BetweennessCentrality build(
                Graph graph, CONFIG configuration, AllocationTracker tracker, Log log
            ) {
                return new BetweennessCentrality(
                    graph,
                    strategy(configuration, graph, tracker),
                    Pools.DEFAULT,
                    config.concurrency(),
                    tracker
                );
            }

            @Override
            public MemoryEstimation memoryEstimation(CONFIG configuration) {
                return MemoryEstimations.empty();
            }
        };
    }

    private static BetweennessCentrality.SelectionStrategy strategy(
        BetweennessCentralityBaseConfig configuration,
        Graph graph,
        AllocationTracker tracker
    ) {
        switch (configuration.strategy()) {
            case "degree":
                return new RandomDegreeSelectionStrategy(
                    graph,
                    0.0,
                    Pools.DEFAULT,
                    configuration.concurrency(),
                    tracker
                );
            case "random":
                double probability = configuration.probability();
                if (Double.isNaN(probability)) {
                    probability = Math.log10(graph.nodeCount()) / Math.exp(2);
                }
                return new RandomSelectionStrategy(graph, probability, tracker);
            default:
                throw new IllegalArgumentException("Unknown selection strategy: " + configuration.strategy());
        }
    }

    static final class CentralityTranslator implements PropertyTranslator.OfDouble<BetweennessCentrality> {
        public static final CentralityTranslator INSTANCE = new CentralityTranslator();

        @Override
        public double toDouble(BetweennessCentrality pageRank, long nodeId) {
            return pageRank.getCentrality().get((int) nodeId);
        }
    }

    static <PROC_RESULT, CONFIG extends BetweennessCentralityBaseConfig> AbstractResultBuilder<PROC_RESULT> resultBuilder(
        BetweennessCentralityResultBuilder<PROC_RESULT> procResultBuilder,
        AlgoBaseProc.ComputationResult<BetweennessCentrality, BetweennessCentrality, CONFIG> computeResult
    ) {
        var result = computeResult.result();
        if (result != null) {
            var centrality = result.getCentrality();

            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;
            double sum = 0.0;
            for (long i = centrality.size() - 1; i >= 0; i--) {
                double c = centrality.get(i);
                if (c < min) {
                    min = c;
                }
                if (c > max) {
                    max = c;
                }
                sum += c;
            }

            procResultBuilder
                .minCentrality(min)
                .maxCentrality(max)
                .sumCentrality(sum);
        }
        return procResultBuilder;
    }

    abstract static class BetweennessCentralityResultBuilder<PROC_RESULT> extends AbstractResultBuilder<PROC_RESULT> {

        double minCentrality = -1;

        double maxCentrality = -1;

        double sumCentrality = -1;

        BetweennessCentralityResultBuilder<PROC_RESULT> minCentrality(double minCentrality) {
            this.minCentrality = minCentrality;
            return this;
        }

        BetweennessCentralityResultBuilder<PROC_RESULT> maxCentrality(double maxCentrality) {
            this.maxCentrality = maxCentrality;
            return this;
        }

        BetweennessCentralityResultBuilder<PROC_RESULT> sumCentrality(double sumCentrality) {
            this.sumCentrality = sumCentrality;
            return this;
        }
    }
}
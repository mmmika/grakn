/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.graql.analytics;

import ai.grakn.GraknTx;
import ai.grakn.concept.Label;
import ai.grakn.graql.ComputeQuery;

import java.util.Collection;
import java.util.Optional;

/**
 * Compute the sum of the selected resource-type.
 *
 * @author Jason Liu
 */
public interface SumQuery extends ComputeQuery<Optional<Number>> {

    /**
     * @param resourceTypeLabels an array of types of resources to execute the query on
     * @return a SumQuery with the subTypeLabels set
     */
    SumQuery of(String... resourceTypeLabels);

    /**
     * @param resourceLabels a collection of types of resources to execute the query on
     * @return a SumQuery with the subTypeLabels set
     */
    SumQuery of(Collection<Label> resourceLabels);

    /**
     * @param subTypeLabels an array of types to include in the subgraph
     * @return a SumQuery with the subTypeLabels set
     */
    @Override
    SumQuery in(String... subTypeLabels);

    /**
     * @param subLabels a collection of types to include in the subgraph
     * @return a SumQuery with the subLabels set
     */
    @Override
    SumQuery in(Collection<Label> subLabels);

    /**
     * Execute the query.
     *
     * @return the sum if the given resource types have instances, otherwise an empty Optional instance
     */
    @Override
    Optional<Number> execute();

    /**
     * @param tx the transaction to execute the query on
     * @return a SumQuery with the transaction set
     */
    @Override
    SumQuery withTx(GraknTx tx);
}

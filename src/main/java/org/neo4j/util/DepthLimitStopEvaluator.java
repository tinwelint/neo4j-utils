/**
 * Copyright (c) 2002-2012 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.util;

import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.TraversalPosition;

/**
 * A {@link StopEvaluator} which stops after X levels.
 * @author mattias
 */
public class DepthLimitStopEvaluator implements StopEvaluator
{
	private int maxDepth;
	
	/**
	 * @param maxDepth the depth to go to before stopping.
	 */
	public DepthLimitStopEvaluator( int maxDepth )
	{
		this.maxDepth = maxDepth;
	}

	public boolean isStopNode( TraversalPosition position )
	{
		return position.depth() >= maxDepth;
	}
}

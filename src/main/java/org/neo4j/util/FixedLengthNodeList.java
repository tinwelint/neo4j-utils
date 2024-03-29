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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ReturnableEvaluator;
import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.TraversalPosition;
import org.neo4j.graphdb.Traverser;
import org.neo4j.graphdb.Traverser.Order;

/**
 * Wraps a linked list of nodes. It has a max length specified so that
 * only the latest N are stored (latest added is first in list).
 */
public class FixedLengthNodeList
{
    private static final String KEY_LENGTH = "list_length";
    
	private Node rootNode;
	private RelationshipType relType;
	private Integer maxLength;
	
	public FixedLengthNodeList( Node rootNode,
	    RelationshipType relType, Integer maxLengthOrNull )
	{
		this.rootNode = rootNode;
		this.relType = relType;
		this.maxLength = maxLengthOrNull;
	}
	
	private Relationship getFirstRelationship()
	{
		return rootNode.getSingleRelationship( relType, Direction.OUTGOING );
	}
	
	private Relationship getLastRelationship()
	{
		return rootNode.getSingleRelationship( relType, Direction.INCOMING );
	}
	
	public Node add()
	{
	    GraphDatabaseUtil.acquireWriteLock( rootNode );
		Node node = rootNode.getGraphDatabase().createNode();
		Relationship rel = getFirstRelationship();
        rootNode.createRelationshipTo( node, relType );
		if ( rel == null )
		{
			node.createRelationshipTo( rootNode, relType );
		}
		else
		{
			Node firstNode = rel.getEndNode();
			rel.delete();
			node.createRelationshipTo( firstNode, relType );
		}
		
		if ( maxLength != null )
		{
			int length = ( Integer ) rootNode.getProperty( KEY_LENGTH, 0 );
			length++;
			if ( length > maxLength )
			{
			    // Remove the last one
			    Relationship lastRel = getLastRelationship();
			    Node lastNode = lastRel.getStartNode();
			    Relationship previousRel = lastNode.getSingleRelationship(
                    relType, Direction.INCOMING );
			    Node previousNode = previousRel.getStartNode();
			    lastRel.delete();
			    previousRel.delete();
			    nodeFellOut( lastNode );
			    previousNode.createRelationshipTo( rootNode, relType );
			}
			else
			{
			    rootNode.setProperty( KEY_LENGTH, length );
			}
		}
		return node;
	}
	
	protected void nodeFellOut( Node lastNode )
    {
	    lastNode.delete();
    }

    public boolean remove()
	{
	    return remove( 1 ) == 1;
	}
	
	public int remove( int max )
	{
	    GraphDatabaseUtil.acquireWriteLock( rootNode );
        Relationship rel = getFirstRelationship();
        int removed = 0;
        if ( rel != null )
        {
            Node node = rel.getEndNode();
            Node nextNode = null;
            for ( int i = 0; i < max; i++ )
            {
                Relationship relToNext = node.getSingleRelationship(
                    relType, Direction.OUTGOING );
                nextNode = relToNext.getEndNode();
                for ( Relationship relToDel : node.getRelationships(
                    relType ) )
                {
                    relToDel.delete();
                }
                nodeFellOut( node );
                removed++;
                if ( nextNode.equals( rootNode ) )
                {
                    break;
                }
                node = nextNode;
            }

            if ( nextNode != null && !nextNode.equals( rootNode ) )
            {
                rootNode.createRelationshipTo( nextNode, relType );
            }
        }
        return removed;
	}
	
	public Node peek()
	{
		Relationship rel = getFirstRelationship();
		Node result = null;
		if ( rel != null )
		{
			result = rel.getEndNode();
		}
		return result;
	}
	
	public Node[] peek( int max )
	{
        Collection<Node> result = new ArrayList<Node>( max );
        Node node = rootNode;
        for ( int i = 0; i < max; i++ )
        {
            Relationship rel = node.getSingleRelationship( relType,
                Direction.OUTGOING );
            if ( rel == null )
            {
                break;
            }
            Node otherNode = rel.getEndNode();
            if ( otherNode.equals( rootNode ) )
            {
                break;
            }
            result.add( otherNode );
            node = otherNode;
        }
        return result.toArray( new Node[ 0 ] );
	}
	
	public Iterator<Node> iterate()
	{
	    StopEvaluator stopEvaluator = new StopEvaluator()
        {
            public boolean isStopNode( TraversalPosition pos )
            {
                return pos.lastRelationshipTraversed() != null &&
                    pos.currentNode().equals( rootNode );
            }
        };
	    
	    Traverser traverser = rootNode.traverse( Order.BREADTH_FIRST,
	        stopEvaluator, ReturnableEvaluator.ALL_BUT_START_NODE, relType,
	        Direction.OUTGOING );
	    return traverser.iterator();
	}
}

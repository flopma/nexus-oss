/**
 * Sonatype Nexus (TM) [Open Source Version].
 * Copyright (c) 2008 Sonatype, Inc. All rights reserved.
 * Includes the third-party code listed at ${thirdPartyUrl}.
 *
 * This program is licensed to you under Version 3 only of the GNU
 * General Public License as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License Version 3 for more details.
 *
 * You should have received a copy of the GNU General Public License
 * Version 3 along with this program. If not, see http://www.gnu.org/licenses/.
 */
package org.sonatype.nexus.proxy.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sonatype.nexus.proxy.EventMulticasterComponent;
import org.sonatype.nexus.proxy.NoSuchRepositoryException;
import org.sonatype.nexus.proxy.NoSuchRepositoryGroupException;
import org.sonatype.nexus.proxy.events.AbstractEvent;
import org.sonatype.nexus.proxy.events.EventListener;
import org.sonatype.nexus.proxy.events.EventMulticaster;
import org.sonatype.nexus.proxy.events.RepositoryRegistryEventAdd;
import org.sonatype.nexus.proxy.events.RepositoryRegistryEventRemove;
import org.sonatype.nexus.proxy.events.RepositoryRegistryEventUpdate;
import org.sonatype.nexus.proxy.events.RepositoryRegistryGroupEventAdd;
import org.sonatype.nexus.proxy.events.RepositoryRegistryGroupEventRemove;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.proxy.repository.RepositoryStatusCheckerThread;

/**
 * The repo registry. It holds handles to registered repositories and sorts them properly. This class is used to get a
 * grip on repositories.
 * <p>
 * Getting reposes from here and changing repo attributes like group, id and rank have no effect on repo registry! For
 * that kind of change, you have to: 1) get repository, 2) remove repository from registry, 3) change repo attributes
 * and 4) add repository.
 * <p>
 * ProximityEvents: this component just "concentrates" the repositiry events of all known repositories by it. It can be
 * used as single point to access all repository events.
 * 
 * @author cstamas
 * @plexus.component
 */
public class DefaultRepositoryRegistry
    extends EventMulticasterComponent
    implements RepositoryRegistry, EventListener
{
    /** The repo register, [Repository.getId, Repository] */
    private Map<String, Repository> repositories = new HashMap<String, Repository>();

    /** The repo status checkrs */
    private Map<String, RepositoryStatusCheckerThread> repositoryStatusCheckers = new HashMap<String, RepositoryStatusCheckerThread>();

    /** The group repo register, (key=Repository.getGroupId, value=List of RepoId's) */
    private Map<String, List<String>> repositoryGroups = new HashMap<String, List<String>>();

    /** The group repo register, (key=Repository.getGroupId, value=List of RepoId's) */
    private Map<String, ContentClass> repositoryGroupContentClasses = new HashMap<String, ContentClass>();

    protected void insertRepository( Repository repository, boolean newlyAdded )
    {
        repositories.put( repository.getId(), repository );

        RepositoryStatusCheckerThread thread = new RepositoryStatusCheckerThread( repository );

        repositoryStatusCheckers.put( repository.getId(), thread );

        if ( newlyAdded )
        {
            if ( repository instanceof EventMulticaster )
            {
                ( (EventMulticaster) repository ).addProximityEventListener( this );
            }

            notifyProximityEventListeners( new RepositoryRegistryEventAdd( this, repository ) );
        }

        thread.setDaemon( true );

        thread.start();
    }

    public void addRepository( Repository repository )
    {
        insertRepository( repository, true );

        getLogger().info(
            "Added repository ID=" + repository.getId() + " (contentClass="
                + repository.getRepositoryContentClass().getId() + ")" );
    }

    public void updateRepository( Repository repository )
        throws NoSuchRepositoryException
    {
        if ( repositories.containsKey( repository.getId() ) )
        {
            RepositoryStatusCheckerThread thread = repositoryStatusCheckers.get( repository.getId() );

            thread.interrupt();

            insertRepository( repository, false );

            notifyProximityEventListeners( new RepositoryRegistryEventUpdate( this, repository ) );

            getLogger().info(
                "Updated repository ID=" + repository.getId() + " (contentClass="
                    + repository.getRepositoryContentClass().getId() + ")" );
        }
        else
        {
            throw new NoSuchRepositoryException( repository.getId() );
        }
    }

    public void removeRepository( String repoId )
        throws NoSuchRepositoryException
    {
        Repository repository = getRepository( repoId );

        notifyProximityEventListeners( new RepositoryRegistryEventRemove( this, repository ) );
        
        removeRepositorySilently( repoId );
    }

    public void removeRepositorySilently( String repoId )
        throws NoSuchRepositoryException
    {
        if ( repositories.containsKey( repoId ) )
        {
            Repository repository = (Repository) repositories.get( repoId );

            for ( String groupId : repositoryGroups.keySet() )
            {
                List<String> groupOrder = repositoryGroups.get( groupId );

                groupOrder.remove( repository.getId() );
            }

            repositories.remove( repository.getId() );

            RepositoryStatusCheckerThread thread = repositoryStatusCheckers.remove( repository.getId() );

            thread.interrupt();

            if ( repository instanceof EventMulticaster )
            {
                ( (EventMulticaster) repository ).removeProximityEventListener( this );
            }

            getLogger().info( "Removed repository id=" + repository.getId() );
        }
        else
        {
            throw new NoSuchRepositoryException( repoId );
        }
    }

    public void addRepositoryGroup( String groupId, List<String> memberRepositories )
        throws NoSuchRepositoryException,
            InvalidGroupingException
    {
        try
        {
            List<String> groupOrder = new ArrayList<String>( memberRepositories != null ? memberRepositories.size() : 0 );

            ContentClass contentClass = null;

            if ( memberRepositories != null )
            {
                for ( String repoId : memberRepositories )
                {
                    Repository repository = getRepository( repoId );

                    if ( contentClass == null )
                    {
                        contentClass = repository.getRepositoryContentClass();
                    }
                    else if ( !contentClass.isCompatible( repository.getRepositoryContentClass() ) )
                    {
                        throw new InvalidGroupingException( contentClass, repository.getRepositoryContentClass() );
                    }

                    groupOrder.add( repository.getId() );
                }
            }

            repositoryGroups.put( groupId, groupOrder );

            repositoryGroupContentClasses.put( groupId, contentClass );

            notifyProximityEventListeners( new RepositoryRegistryGroupEventAdd( this, groupId ) );

            getLogger().info(
                "Added repository group ID=" + groupId + " (contentClass="
                    + ( contentClass != null ? contentClass.getId() : "null" )
                    + ") with repository members of (in processing order) " + memberRepositories );
        }
        catch ( NoSuchRepositoryException e )
        {
            repositoryGroups.remove( groupId );

            throw e;
        }
    }

    public void removeRepositoryGroup( String groupId )
        throws NoSuchRepositoryGroupException
    {
        removeRepositoryGroup( groupId, false );
    }

    public void removeRepositoryGroup( String groupId, boolean withRepositories )
        throws NoSuchRepositoryGroupException
    {
        if ( repositoryGroups.containsKey( groupId ) )
        {
            notifyProximityEventListeners( new RepositoryRegistryGroupEventRemove( this, groupId ) );
            
            if ( withRepositories )
            {
                List<Repository> groupOrder = getRepositoryGroup( groupId );

                for ( Repository repository : groupOrder )
                {
                    try
                    {
                        removeRepository( repository.getId() );
                    }
                    catch ( NoSuchRepositoryException ex )
                    {
                        // this should not happen
                        getLogger().warn(
                            "Got NoSuchRepositoryException while removing group " + groupId + ", ignoring it.",
                            ex );
                    }
                }
            }

            repositoryGroups.remove( groupId );
        }
        else
        {
            throw new NoSuchRepositoryGroupException( groupId );
        }
    }

    public Repository getRepository( String repoId )
        throws NoSuchRepositoryException
    {
        if ( repositories.containsKey( repoId ) )
        {
            return repositories.get( repoId );
        }
        else
        {
            throw new NoSuchRepositoryException( repoId );
        }
    }

    public List<Repository> getRepositories()
    {
        List<Repository> result = new ArrayList<Repository>( repositories.keySet().size() );

        for ( String repoId : repositories.keySet() )
        {
            result.add( repositories.get( repoId ) );
        }

        return Collections.unmodifiableList( result );
    }

    public List<String> getRepositoryGroupIds()
    {
        List<String> result = new ArrayList<String>( repositoryGroups.size() );

        for ( String repoGroupId : repositoryGroups.keySet() )
        {
            result.add( repoGroupId );
        }

        return Collections.unmodifiableList( result );
    }

    public List<Repository> getRepositoryGroup( String groupId )
        throws NoSuchRepositoryGroupException
    {
        if ( repositoryGroups.containsKey( groupId ) )
        {
            List<String> groupOrder = repositoryGroups.get( groupId );

            List<Repository> result = new ArrayList<Repository>( groupOrder.size() );

            for ( String repoId : groupOrder )
            {
                result.add( repositories.get( repoId ) );
            }

            return Collections.unmodifiableList( result );
        }
        else
        {
            throw new NoSuchRepositoryGroupException( groupId );
        }
    }

    public ContentClass getRepositoryGroupContentClass( String groupId )
        throws NoSuchRepositoryGroupException
    {
        if ( repositoryGroupContentClasses.containsKey( groupId ) )
        {
            return repositoryGroupContentClasses.get( groupId );
        }
        else
        {
            throw new NoSuchRepositoryGroupException( groupId );
        }
    }

    public boolean repositoryIdExists( String repositoryId )
    {
        return repositories.containsKey( repositoryId );
    }

    public boolean repositoryGroupIdExists( String repositoryGroupId )
    {
        return repositoryGroups.containsKey( repositoryGroupId );
    }

    public List<String> getGroupsOfRepository( String repositoryId )
    {
        ArrayList<String> result = new ArrayList<String>();

        for ( String groupId : repositoryGroups.keySet() )
        {
            List<String> memberIds = repositoryGroups.get( groupId );

            if ( memberIds.contains( repositoryId ) )
            {
                result.add( groupId );
            }
        }

        return result;
    }

    /**
     * Simply "aggregating" repo events, and passing them over.
     */
    public void onProximityEvent( AbstractEvent evt )
    {
        this.notifyProximityEventListeners( evt );
    }

}

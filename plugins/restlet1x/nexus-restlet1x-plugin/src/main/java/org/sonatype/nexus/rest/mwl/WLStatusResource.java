/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.rest.mwl;

import java.util.concurrent.TimeUnit;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import org.codehaus.enunciate.contract.jaxrs.ResourceMethodSignature;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.restlet.Context;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.resource.ResourceException;
import org.restlet.resource.Variant;
import org.sonatype.nexus.proxy.maven.MavenProxyRepository;
import org.sonatype.nexus.proxy.maven.MavenRepository;
import org.sonatype.nexus.proxy.maven.wl.WLDiscoveryConfig;
import org.sonatype.nexus.proxy.maven.wl.WLDiscoveryStatus;
import org.sonatype.nexus.proxy.maven.wl.WLDiscoveryStatus.DStatus;
import org.sonatype.nexus.proxy.maven.wl.WLPublishingStatus;
import org.sonatype.nexus.proxy.maven.wl.WLPublishingStatus.PStatus;
import org.sonatype.nexus.proxy.maven.wl.WLStatus;
import org.sonatype.nexus.rest.RepositoryURLBuilder;
import org.sonatype.nexus.rest.model.WLDiscoveryStatusMessage;
import org.sonatype.nexus.rest.model.WLStatusMessage;
import org.sonatype.nexus.rest.model.WLStatusMessageWrapper;
import org.sonatype.plexus.rest.resource.PlexusResource;

import com.google.common.primitives.Ints;

/**
 * WL Status REST resource.
 * 
 * @author cstamas
 * @since 2.4
 */
@Component( role = PlexusResource.class, hint = "WLStatusResource" )
@Path( WLStatusResource.RESOURCE_URI )
@Produces( { "application/xml", "application/json" } )
public class WLStatusResource
    extends WLResourceSupport
{
    /**
     * REST resource URI.
     */
    public static final String RESOURCE_URI = "/repositories/{" + REPOSITORY_ID_KEY + "}/wl";

    @Requirement( hint = "RestletRepositoryUrlBuilder" )
    private RepositoryURLBuilder repositoryURLBuilder;

    @Override
    public Object getPayloadInstance()
    {
        return new WLStatusMessageWrapper();
    }

    @Override
    public String getResourceUri()
    {
        return RESOURCE_URI;
    }

    @Override
    @GET
    @ResourceMethodSignature( pathParams = { @PathParam( REPOSITORY_ID_KEY ) }, output = WLStatusMessageWrapper.class )
    public WLStatusMessageWrapper get( final Context context, final Request request, final Response response,
                                       final Variant variant )
        throws ResourceException
    {
        final MavenRepository mavenRepository = getMavenRepository( request, MavenRepository.class );
        final WLStatus status = getWLManager().getStatusFor( mavenRepository );
        final WLStatusMessage payload = new WLStatusMessage();
        switch ( status.getPublishingStatus().getStatus() )
        {
            case PUBLISHED:
                payload.setPublishedStatus( 1 );
                break;
            case NOT_PUBLISHED:
                payload.setPublishedStatus( -1 );
                break;
            default:
                payload.setPublishedStatus( 0 );
                break;
        }
        payload.setPublishedMessage( status.getPublishingStatus().getLastPublishedMessage() );
        final WLPublishingStatus pstatus = status.getPublishingStatus();
        if ( PStatus.PUBLISHED == pstatus.getStatus() )
        {
            payload.setPublishedTimestamp( pstatus.getLastPublishedTimestamp() );
            if ( pstatus.getLastPublishedFilePath() != null )
            {
                final String repositoryUrl = repositoryURLBuilder.getExposedRepositoryContentUrl( mavenRepository );
                if ( repositoryUrl != null )
                {
                    payload.setPublishedUrl( repositoryUrl + pstatus.getLastPublishedFilePath() );
                }
            }
        }
        else
        {
            payload.setPublishedTimestamp( -1 );
            payload.setPublishedUrl( null );
        }
        if ( DStatus.NOT_A_PROXY == status.getDiscoveryStatus().getStatus() )
        {
            payload.setDiscovery( null );
        }
        else
        {
            final WLDiscoveryStatus dstatus = status.getDiscoveryStatus();
            final WLDiscoveryStatusMessage discoveryPayload = new WLDiscoveryStatusMessage();
            payload.setDiscovery( discoveryPayload );
            if ( DStatus.DISABLED == status.getDiscoveryStatus().getStatus() )
            {
                discoveryPayload.setDiscoveryEnabled( false );
            }
            else
            {
                final MavenProxyRepository mavenProxyRepository =
                    getMavenRepository( request, MavenProxyRepository.class );
                final WLDiscoveryConfig config = getWLManager().getRemoteDiscoveryConfig( mavenProxyRepository );
                discoveryPayload.setDiscoveryEnabled( true );
                discoveryPayload.setDiscoveryIntervalHours( Ints.saturatedCast( TimeUnit.MILLISECONDS.toHours( config.getDiscoveryInterval() ) ) );
                discoveryPayload.setDiscoveryLastStatus( 0 );

                // if we have it run at all
                if ( DStatus.ENABLED.ordinal() < status.getDiscoveryStatus().getStatus().ordinal() )
                {
                    if ( DStatus.SUCCESSFUL == status.getDiscoveryStatus().getStatus() )
                    {
                        discoveryPayload.setDiscoveryLastStatus( 1 );
                    }
                    else
                    {
                        discoveryPayload.setDiscoveryLastStatus( -1 );
                    }
                    // last- messages
                    discoveryPayload.setDiscoveryLastStrategy( dstatus.getLastDiscoveryStrategy() );
                    discoveryPayload.setDiscoveryLastMessage( dstatus.getLastDiscoveryMessage() );
                    discoveryPayload.setDiscoveryLastRunTimestamp( dstatus.getLastDiscoveryTimestamp() );
                }
            }
        }

        final WLStatusMessageWrapper responseNessage = new WLStatusMessageWrapper();
        responseNessage.setData( payload );
        return responseNessage;
    }

    /**
     * Force updates WL for given proxy repository. If invoked for non-Maven proxy repository, response is Bad Request.
     */
    @Override
    @DELETE
    @ResourceMethodSignature( pathParams = { @PathParam( REPOSITORY_ID_KEY ) } )
    public void delete( final Context context, final Request request, final Response response )
        throws ResourceException
    {
        final MavenProxyRepository mavenRepository = getMavenRepository( request, MavenProxyRepository.class );
        getWLManager().updateWhitelist( mavenRepository );
        // currently this happens synchronously, but it is the status that will reveal real outcome of the operation
        response.setStatus( Status.SUCCESS_ACCEPTED );
    }
}

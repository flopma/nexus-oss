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
package org.sonatype.nexus.integrationtests.nexus395;

import javax.mail.internet.MimeMessage;

import org.junit.Assert;
import org.junit.Test;
import org.restlet.data.Status;
import org.sonatype.nexus.integrationtests.AbstractEmailServerNexusIT;

import com.icegreen.greenmail.util.GreenMailUtil;


/**
 * Test forgot username system.  Check if nexus is sending the e-mail. 
 */
public class Nexus395ForgotUsernameTest
    extends AbstractEmailServerNexusIT
{

    @Test
    public void recoverUsername()
        throws Exception
    {
        Status status = ForgotUsernameUtils.recoverUsername( "nexus-dev2@sonatype.org" );
        Assert.assertEquals( Status.SUCCESS_ACCEPTED.getCode(), status.getCode() );

        // Need 1 message
        server.waitForIncomingEmail( 1000, 1 );

        MimeMessage[] msgs = server.getReceivedMessages();

        String username = null;
        // Sample body: Your password has been reset. Your new password is: c1r6g4p8l7
        String body = GreenMailUtil.getBody( msgs[0] );
        
        int index = body.indexOf( " - \"" );
        int usernameStartIndex = index + " - \"".length();
        if ( index != -1 )
        {
            username = body.substring( usernameStartIndex, body.indexOf( '\"', usernameStartIndex ) ).trim();
            log.debug( "Username:\n" + username );
        }

        Assert.assertNotNull( username );
    }

}

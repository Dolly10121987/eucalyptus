/*******************************************************************************
 * Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.component;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.log4j.Logger;
import com.eucalyptus.component.Component.State;
import com.eucalyptus.component.Component.Transition;
import com.eucalyptus.component.auth.SystemCredentialProvider;
import com.eucalyptus.empyrean.Empyrean;
import com.eucalyptus.empyrean.ServiceId;
import com.eucalyptus.event.ClockTick;
import com.eucalyptus.event.Event;
import com.eucalyptus.event.EventListener;
import com.eucalyptus.event.Hertz;
import com.eucalyptus.event.ListenerRegistry;
import com.eucalyptus.system.Threads;
import com.eucalyptus.util.FullName;
import com.eucalyptus.util.HasFullName;
import com.eucalyptus.util.HasParent;
import com.eucalyptus.util.async.CheckedListenableFuture;
import com.eucalyptus.util.async.Futures;
import com.eucalyptus.util.async.Request;
import com.eucalyptus.util.fsm.ExistingTransitionException;

public class DisabledService implements Service {
  private static Logger              LOG  = Logger.getLogger( DisabledService.class );
  private final ServiceConfiguration serviceConfiguration;
  
  DisabledService( ServiceConfiguration serviceConfiguration ) {
    this.serviceConfiguration = serviceConfiguration;
  }
  
  @Override
  public final String getName( ) {
    return this.serviceConfiguration.getFullName( ).toString( );
  }
  
  @Override
  public Component.State getState( ) {
    return Component.State.PRIMORDIAL;
  }
  
  /** TODO:GRZE: clean this up **/
  @Override
  public final ServiceId getServiceId( ) {
    return new ServiceId( ) {
      {
        this.setUuid( DisabledService.this.serviceConfiguration.getFullName( ).toString( ) );
        this.setPartition( DisabledService.this.serviceConfiguration.getPartition( ) );
        this.setName( DisabledService.this.serviceConfiguration.getName( ) );
        this.setType( DisabledService.this.serviceConfiguration.getComponentId( ).getName( ) );
        this.setUri( DisabledService.this.serviceConfiguration.getUri( ).toString( ) );
      }
    };
  }
  
  @Override
  public Boolean isLocal( ) {
    return this.serviceConfiguration.isLocal( );
  }
  
  @Override
  public KeyPair getKeys( ) {
    return SystemCredentialProvider.getCredentialProvider( this.serviceConfiguration.getComponentId( ) ).getKeyPair( );
  }
  
  @Override
  public X509Certificate getCertificate( ) {
    return SystemCredentialProvider.getCredentialProvider( this.serviceConfiguration.getComponentId( ) ).getCertificate( );
  }
  
  /**
   * @see java.lang.Comparable#compareTo(java.lang.Object)
   * @param that
   * @return
   */
  @Override
  public int compareTo( MessagableService that ) {
    if ( this.getServiceConfiguration( ).getPartition( ).equals( that.getServiceConfiguration( ).getPartition( ) ) ) {
      if ( that.getState( ).ordinal( ) == this.getState( ).ordinal( ) ) {
        return this.getName( ).compareTo( that.getName( ) );
      } else {
        return that.getState( ).ordinal( ) - this.getState( ).ordinal( );
      }
    } else {
      return this.getServiceConfiguration( ).getPartition( ).compareTo( that.getServiceConfiguration( ).getPartition( ) );
    }
  }
  
  /**
   * @return the service configuration
   */
  @Override
  public ServiceConfiguration getServiceConfiguration( ) {
    return this.serviceConfiguration;
  }
  
  @Override
  public Component getComponent( ) {
    return this.serviceConfiguration.lookupComponent( );
  }
  
  @Override
  public ComponentId getComponentId( ) {
    return this.serviceConfiguration.getComponentId( );
  }
  
  @Override
  public FullName getFullName( ) {
    return this.serviceConfiguration.getFullName( );
  }
  
  @Override
  public String toString( ) {
    return String.format( "Service %s name=%s serviceConfiguration=%s\n",
                          this.getComponentId( ), this.getName( ), this.getServiceConfiguration( ) );
  }
  
  @Override
  public String getPartition( ) {
    return this.serviceConfiguration.getPartition( );
  }
  
  @Override
  public Component getParent( ) {
    return this.getComponent( );
  }
  
  @Override
  public Dispatcher getDispatcher( ) {
    throw new RuntimeException("This service does not support the operation: " + Thread.currentThread().getStackTrace()[0] );
  }
  
  @Override
  public List<String> getDetails( ) {
    throw new RuntimeException("This service does not support the operation: " + Thread.currentThread().getStackTrace()[0] );
  }
  

  @Override
  public ServiceEndpoint getEndpoint( ) {
    throw new RuntimeException("This service does not support the operation: " + Thread.currentThread().getStackTrace()[0] );
  }

  @Override
  public void enqueue( Request request ) {
    LOG.error( "Discarding request submitted to a basic service: " + request );
  }
  
  @Override
  public boolean checkTransition( Transition transition ) {
    return false;
  }
  
  @Override
  public Component.State getGoal( ) {
    return Component.State.PRIMORDIAL;
  }
  
  @Override
  public CheckedListenableFuture<ServiceConfiguration> transition( Transition transition ) throws IllegalStateException, NoSuchElementException, ExistingTransitionException {
    return Futures.predestinedFuture( this.serviceConfiguration );
  }
  
  @Override
  public CheckedListenableFuture<ServiceConfiguration> transition( State state ) throws IllegalStateException, NoSuchElementException, ExistingTransitionException {
    return Futures.predestinedFuture( this.serviceConfiguration );
  }
  
  @Override
  public CheckedListenableFuture<ServiceConfiguration> transitionSelf( ) {
    return Futures.predestinedFuture( this.serviceConfiguration );
  }
  
  @Override
  public InetSocketAddress getSocketAddress( ) {
    throw new RuntimeException("This service does not support the operation: " + Thread.currentThread().getStackTrace()[0] );
  }

  @Override
  public void setGoal( State state ) {}
  
}

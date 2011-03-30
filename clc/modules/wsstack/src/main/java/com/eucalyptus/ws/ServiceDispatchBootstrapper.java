/*******************************************************************************
 *Copyright (c) 2009  Eucalyptus Systems, Inc.
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
 *    THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************/
/*
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */
package com.eucalyptus.ws;

import java.util.NoSuchElementException;
import org.apache.log4j.Logger;
import com.eucalyptus.bootstrap.Bootstrap;
import com.eucalyptus.bootstrap.BootstrapException;
import com.eucalyptus.bootstrap.Bootstrapper;
import com.eucalyptus.bootstrap.Provides;
import com.eucalyptus.bootstrap.RunDuring;
import com.eucalyptus.component.Component;
import com.eucalyptus.component.ComponentId;
import com.eucalyptus.component.ComponentIds;
import com.eucalyptus.component.Components;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.ServiceRegistrationException;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.empyrean.Empyrean;
import com.eucalyptus.records.EventRecord;
import com.eucalyptus.records.EventType;
import com.eucalyptus.system.Threads;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.ws.client.ServiceDispatcher;

@Provides( Empyrean.class )
@RunDuring( Bootstrap.Stage.RemoteServicesInit )
public class ServiceDispatchBootstrapper extends Bootstrapper {
  private static Logger LOG = Logger.getLogger( ServiceDispatchBootstrapper.class );
  
  @Override
  public boolean load( ) throws Exception {
    /**
     * TODO: ultimately remove this: it is legacy and enforces a one-to-one
     * relationship between component impls
     **/
    for ( ComponentId c : ComponentIds.list( ) ) {
      if ( c.hasDispatcher( ) && c.isAlwaysLocal( ) ) {
        try {
          Component comp = Components.lookup( c );
        } catch ( NoSuchElementException e ) {
          throw BootstrapException.throwFatal( "Failed to lookup component which is alwaysLocal: " + c.name( ), e );
        }
      } else if ( c.hasDispatcher( ) ) {
        try {
          Component comp = Components.lookup( c );
        } catch ( NoSuchElementException e ) {
          Exceptions.eat( "Failed to lookup component which may have dispatcher references: " + c.name( ), e );
        }
      }
    }
    LOG.trace( "Touching class: " + ServiceDispatcher.class );
    boolean failed = false;
    Component euca = Components.lookup( Eucalyptus.class );
    for ( final Component comp : Components.list( ) ) {
      EventRecord.here( ServiceVerifyBootstrapper.class, EventType.COMPONENT_INFO, comp.getName( ), comp.isAvailableLocally( ).toString( ) ).info( );
      for ( final ServiceConfiguration s : comp.lookupServiceConfigurations( ) ) {
        if ( euca.isLocal( ) && euca.getComponentId( ).hasDispatcher( ) ) {
          Threads.lookup( Empyrean.class, ServiceDispatchBootstrapper.class ).submit( new Runnable( ) {
            
            @Override
            public void run( ) {
              try {
                comp.loadService( s ).get( );
              } catch ( ServiceRegistrationException ex ) {
                LOG.error( ex, ex );//TODO:GRZE: report error
              } catch ( Throwable ex ) {
                Exceptions.trace( "load(): Building service failed: " + Components.componentToString( ).apply( comp ), ex );
              }
              
            }
          } );
        }
      }
    }
    return true;
  }
  
  @Override
  public boolean start( ) throws Exception {
    Component euca = Components.lookup( Eucalyptus.class );
    for ( final Component comp : Components.list( ) ) {
      EventRecord.here( ServiceVerifyBootstrapper.class, EventType.COMPONENT_INFO, comp.getName( ), comp.isAvailableLocally( ).toString( ) ).info( );
      for ( final ServiceConfiguration s : comp.lookupServiceConfigurations( ) ) {
        if ( euca.isLocal( ) && euca.getComponentId( ).hasDispatcher( ) ) {
          Threads.lookup( Empyrean.class, ServiceDispatchBootstrapper.class ).submit( new Runnable( ) {
            
            @Override
            public void run( ) {
              try {
                comp.enableTransition( s ).get( );
              } catch ( Throwable ex ) {
                Exceptions.trace( "start()/enable(): Starting service failed: " + Components.componentToString( ).apply( comp ), ex );//TODO:GRZE: report error
              }
            }
          } );
        }
      }
    }
    return true;
  }
  
  /**
   * @see com.eucalyptus.bootstrap.Bootstrapper#enable()
   */
  @Override
  public boolean enable( ) throws Exception {
    return true;
  }
  
  /**
   * @see com.eucalyptus.bootstrap.Bootstrapper#stop()
   */
  @Override
  public boolean stop( ) throws Exception {
    return true;
  }
  
  /**
   * @see com.eucalyptus.bootstrap.Bootstrapper#destroy()
   */
  @Override
  public void destroy( ) throws Exception {}
  
  /**
   * @see com.eucalyptus.bootstrap.Bootstrapper#disable()
   */
  @Override
  public boolean disable( ) throws Exception {
    return true;
  }
  
  /**
   * @see com.eucalyptus.bootstrap.Bootstrapper#check()
   */
  @Override
  public boolean check( ) throws Exception {
    return true;
  }
}

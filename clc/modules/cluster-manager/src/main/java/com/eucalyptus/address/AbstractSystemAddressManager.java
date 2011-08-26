package com.eucalyptus.address;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.apache.log4j.Logger;
import com.eucalyptus.auth.principal.Principals;
import com.eucalyptus.cloud.util.NotEnoughResourcesException;
import com.eucalyptus.cluster.Cluster;
import com.eucalyptus.cluster.ClusterState;
import com.eucalyptus.cluster.VmInstance;
import com.eucalyptus.cluster.VmInstance.VmState;
import com.eucalyptus.cluster.VmInstance.VmStateSet;
import com.eucalyptus.cluster.callback.UnassignAddressCallback;
import com.eucalyptus.cluster.VmInstances;
import com.eucalyptus.component.Partition;
import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.records.EventRecord;
import com.eucalyptus.records.EventType;
import com.eucalyptus.records.Logs;
import com.eucalyptus.util.LogUtil;
import com.eucalyptus.util.OwnerFullName;
import com.eucalyptus.util.async.AsyncRequests;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import edu.ucsb.eucalyptus.cloud.exceptions.ExceptionList;
import edu.ucsb.eucalyptus.msgs.ClusterAddressInfo;

public abstract class AbstractSystemAddressManager {
  private final static Logger                                              LOG     = Logger.getLogger( AbstractSystemAddressManager.class );
  private static final ConcurrentNavigableMap<ClusterAddressInfo, Integer> orphans = new ConcurrentSkipListMap<ClusterAddressInfo, Integer>( );
  
  public static void clearOrphan( ClusterAddressInfo address ) {
    Integer delay = orphans.remove( address );
    delay = ( delay == null
      ? 0
      : delay );
    if ( delay > 2 ) {
      LOG.warn( "Forgetting stale orphan address mapping for " + address.toString( ) );
    }
  }
  
  public static void handleOrphan( String cluster, ClusterAddressInfo address ) {
    Integer orphanCount = 1;
    orphanCount = orphans.putIfAbsent( address, orphanCount );
    orphanCount = ( orphanCount == null )
      ? 1
      : orphanCount;
    orphans.put( address, orphanCount + 1 );
    EventRecord.caller( ClusterState.class, EventType.ADDRESS_STATE,
                        "Updated orphaned public ip address: " + LogUtil.dumpObject( address ) + " count=" + orphanCount ).debug( );
    if ( orphanCount > AddressingConfiguration.ADDRESS_ORPHAN_TICKS ) {
      EventRecord.caller( ClusterState.class, EventType.ADDRESS_STATE,
                          "Unassigning orphaned public ip address: " + LogUtil.dumpObject( address ) + " count=" + orphanCount ).warn( );
      try {
        final Address addr = Addresses.getInstance( ).lookup( address.getAddress( ) );
        if ( addr.isAssigned( ) ) {
          AsyncRequests.newRequest( new UnassignAddressCallback( address ) ).dispatch( cluster );
        } else if ( addr.isSystemOwned( ) ) {
          addr.release( );
        }
      } catch ( NoSuchElementException e ) {}
      orphans.remove( address );
    }
  }
  
  public Address allocateNext( final OwnerFullName userId ) throws NotEnoughResourcesException {
    final Address addr = Addresses.getInstance( ).enableFirst( ).allocate( userId );
    LOG.debug( "Allocated address for public addressing: " + addr.toString( ) );
    if ( addr == null ) {
      LOG.debug( LogUtil.header( Addresses.getInstance( ).toString( ) ) );
      throw new NotEnoughResourcesException( ExceptionList.ERR_SYS_INSUFFICIENT_ADDRESS_CAPACITY );
    }
    return addr;
  }
  
  public abstract void assignSystemAddress( final VmInstance vm ) throws NotEnoughResourcesException;
  
  public abstract List<Address> getReservedAddresses( );
  
  public abstract void inheritReservedAddresses( List<Address> previouslyReservedAddresses );
  
  public abstract List<Address> allocateSystemAddresses( Partition partition, int count ) throws NotEnoughResourcesException;
  
  public Address allocateSystemAddress( final Partition partition ) throws NotEnoughResourcesException {
    return this.allocateSystemAddresses( partition, 1 ).get( 0 );
    
  }
  
  public void update( final Cluster cluster, final List<ClusterAddressInfo> ccList ) {
    if ( !cluster.getState( ).isAddressingInitialized( ) ) {
      Helper.loadStoredAddresses( cluster );
      cluster.getState( ).setAddressingInitialized( true );
    }
    for ( final ClusterAddressInfo addrInfo : ccList ) {
      try {
        final Address address = Helper.lookupOrCreate( cluster, addrInfo );
        if ( address.isAssigned( ) && !addrInfo.hasMapping( ) && !address.isPending( ) ) {
          if ( Principals.nobodyFullName( ).equals( address.getOwner( ) ) ) {
            Helper.markAsAllocated( cluster, addrInfo, address );
          }
          try {
            final VmInstance vm = VmInstances.lookupByInstanceIp( addrInfo.getInstanceIp( ) );
            clearOrphan( addrInfo );
          } catch ( final NoSuchElementException e ) {
            try {
              final VmInstance vm = VmInstances.lookup( address.getInstanceId( ) );
              clearOrphan( addrInfo );
            } catch ( final NoSuchElementException ex ) {
              InetAddress addr = null;
              try {
                addr = Inet4Address.getByName( addrInfo.getInstanceIp( ) );
              } catch ( final UnknownHostException e1 ) {
                LOG.debug( e1, e1 );
              }
              if ( ( addr == null ) || !addr.isLoopbackAddress( ) ) {
                handleOrphan( cluster.getName( ), addrInfo );
              }
            }
          }
        } else if ( address.isAllocated( ) && Principals.nobodyFullName( ).equals( address.getOwner( ) ) && !address.isPending( ) ) {
          Helper.markAsAllocated( cluster, addrInfo, address );
        }
      } catch ( final Exception e ) {
        LOG.debug( e, e );
      }
    }
  }
  
  protected static class Helper {
    protected static Address lookupOrCreate( final Cluster cluster, final ClusterAddressInfo addrInfo ) {
      Address addr = null;
      VmInstance vm = null;
      try {
        addr = Addresses.getInstance( ).lookupDisabled( addrInfo.getAddress( ) );
        LOG.trace( "Found address in the inactive set cache: " + addr );
      } catch ( final NoSuchElementException e1 ) {
        try {
          addr = Addresses.getInstance( ).lookup( addrInfo.getAddress( ) );
          LOG.trace( "Found address in the active set cache: " + addr );
        } catch ( final NoSuchElementException e ) {}
      }
      Helper.checkUniqueness( addrInfo );
      if ( addrInfo.hasMapping( ) ) {
        vm = Helper.maybeFindVm( addr != null
          ? addr.getInstanceId( )
          : null, addrInfo.getAddress( ), addrInfo.getInstanceIp( ) );
        if ( ( addr != null ) && ( vm != null ) ) {
          Helper.ensureAllocated( addr, vm );
          clearOrphan( addrInfo );
        } else if ( addr != null && !addr.isPending( ) && vm != null && VmStateSet.DONE.apply( vm ) ) {
          handleOrphan( cluster.getName( ), addrInfo );
        } else if ( ( addr != null && !addr.isPending( ) ) && ( vm == null ) ) {
          handleOrphan( cluster.getName( ), addrInfo );
        } else if ( ( addr == null ) && ( vm != null ) ) {
          addr = new Address( Principals.systemFullName( ), addrInfo.getAddress( ), cluster.getPartition( ), vm.getInstanceId( ), vm.getPrivateAddress( ) );
          clearOrphan( addrInfo );
        } else if ( ( addr == null ) && ( vm == null ) ) {
          addr = new Address( addrInfo.getAddress( ), cluster.getPartition( ) );
          handleOrphan( cluster.getName( ), addrInfo );
        }
      } else {
        if ( ( addr != null ) && addr.isAssigned( ) && !addr.isPending( ) ) {
          handleOrphan( cluster.getName( ), addrInfo );
        } else if ( ( addr != null ) && !addr.isAssigned( ) && !addr.isPending( ) && addr.isSystemOwned( ) ) {
          try {
            addr.release( );
          } catch ( final Exception ex ) {
            LOG.error( ex );
          }
        } else if ( ( addr != null ) && Address.Transition.system.equals( addr.getTransition( ) ) ) {
          handleOrphan( cluster.getName( ), addrInfo );
        } else if ( addr == null ) {
          addr = new Address( addrInfo.getAddress( ), cluster.getPartition( ) );
          Helper.clearVmState( addrInfo );
        }
      }
      return addr;
    }
    
    private static void markAsAllocated( final Cluster cluster, final ClusterAddressInfo addrInfo, final Address address ) {
      try {
        if ( !address.isPending( ) ) {
          for ( final VmInstance vm : VmInstances.listValues( ) ) {
            if ( addrInfo.getInstanceIp( ).equals( vm.getPrivateAddress( ) ) && VmState.RUNNING.equals( vm.getRuntimeState( ) ) ) {
              LOG.warn( "Out of band address state change: " + LogUtil.dumpObject( addrInfo ) + " address=" + address + " vm=" + vm );
              if ( !address.isAllocated( ) ) {
                address.pendingAssignment( ).assign( vm ).clearPending( );
              } else {
                address.assign( vm ).clearPending( );
              }
              clearOrphan( addrInfo );
              return;
            }
          }
        }
      } catch ( final IllegalStateException e ) {
        LOG.error( e );
      }
    }
    
    private static void clearAddressCachedState( final Address addr ) {
      try {
        if ( !addr.isPending( ) ) {
          addr.unassign( ).clearPending( );
        }
      } catch ( final Exception t ) {
        LOG.trace( t, t );
      }
    }
    
    private static void clearVmState( final ClusterAddressInfo addrInfo ) {
      try {
        final VmInstance vm = VmInstances.lookupByPublicIp( addrInfo.getAddress( ) );
        vm.updatePublicAddress( vm.getPrivateAddress( ) );
      } catch ( final NoSuchElementException e ) {}
    }
    
    private static VmInstance maybeFindVm( final String instanceId, final String publicIp, final String privateIp ) {
      VmInstance vm = null;
      try {
        vm = VmInstances.lookup( instanceId );
      } catch ( NoSuchElementException ex ) {
        try {
          vm = VmInstances.lookupByInstanceIp( privateIp );
        } catch ( final NoSuchElementException e ) {
          Logs.exhaust( ).error( e );
        }
      }
      if ( vm != null ) {
        LOG.trace( "Candidate vm which claims this address: " + vm.getInstanceId( ) + " " + vm.getRuntimeState( ) + " " + publicIp );
        if ( publicIp.equals( vm.getPublicAddress( ) ) ) {
          LOG.trace( "Found vm which claims this address: " + vm.getInstanceId( ) + " " + vm.getState( ) + " " + publicIp );
        }
      }
      return vm;
    }
    
    private static void ensureAllocated( final Address addr, final VmInstance vm ) {
      if ( !addr.isAllocated( ) && !addr.isPending( ) ) {
        try {
          if ( !addr.isAssigned( ) && !addr.isPending( ) ) {
            addr.pendingAssignment( );
            try {
              addr.assign( vm ).clearPending( );
            } catch ( final Exception e1 ) {
              LOG.debug( e1, e1 );
            }
          }
        } catch ( final Exception e1 ) {
          LOG.debug( e1, e1 );
        }
      } else if ( !addr.isAssigned( ) ) {
        try {
          addr.assign( vm ).clearPending( );
        } catch ( final Exception e1 ) {
          LOG.debug( e1, e1 );
        }
      } else {
        LOG.debug( "Address usage checked: " + addr );
      }
    }
    
    private static void checkUniqueness( final ClusterAddressInfo addrInfo ) {
      final Collection<VmInstance> matches = Collections2.filter( VmInstances.listValues( ), VmInstances.withPrivateAddress( addrInfo.getAddress( ) ) );
      if ( matches.size( ) > 1 ) {
        LOG.error( "Found " + matches.size( ) + " vms with the same address: " + addrInfo + " -> " + matches );
      }
    }
    
    protected static void loadStoredAddresses( final Cluster cluster ) {
      try {
        final EntityWrapper<Address> db = EntityWrapper.get( Address.class );
        final Address clusterAddr = new Address( );
        clusterAddr.setCluster( cluster.getPartition( ) );
        List<Address> addrList = Lists.newArrayList( );
        try {
          addrList = db.query( clusterAddr );
          db.commit( );
        } catch ( final Exception e1 ) {
          db.rollback( );
        }
        for ( final Address addr : addrList ) {
          try {
            LOG.info( "Restoring persistent address info for: " + addr );
            Addresses.getInstance( ).lookup( addr.getName( ) );
            addr.init( );
          } catch ( final Exception e ) {
            addr.init( );
          }
        }
      } catch ( final Exception e ) {
        LOG.debug( e, e );
      }
    }
  }
  
}

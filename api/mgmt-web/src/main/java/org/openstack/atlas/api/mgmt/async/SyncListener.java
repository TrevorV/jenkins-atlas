package org.openstack.atlas.api.mgmt.async;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.api.helpers.NodesHelper;
import org.openstack.atlas.docs.loadbalancers.api.v1.SslTermination;
import org.openstack.atlas.service.domain.entities.LoadBalancer;
import org.openstack.atlas.service.domain.entities.LoadBalancerStatus;
import org.openstack.atlas.service.domain.events.UsageEvent;
import org.openstack.atlas.service.domain.exceptions.EntityNotFoundException;
import org.openstack.atlas.service.domain.exceptions.UsageEventCollectionException;
import org.openstack.atlas.service.domain.pojos.Sync;
import org.openstack.atlas.service.domain.pojos.SyncLocation;
import org.openstack.atlas.service.domain.pojos.ZeusSslTermination;
import org.openstack.atlas.service.domain.services.helpers.AlertType;
import org.openstack.atlas.usagerefactor.SnmpUsage;

import javax.jms.Message;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static org.openstack.atlas.service.domain.entities.LoadBalancerStatus.*;
import static org.openstack.atlas.service.domain.entities.NodeStatus.ONLINE;
import static org.openstack.atlas.service.domain.events.entities.CategoryType.CREATE;
import static org.openstack.atlas.service.domain.events.entities.CategoryType.DELETE;
import static org.openstack.atlas.service.domain.events.entities.EventSeverity.INFO;
import static org.openstack.atlas.service.domain.events.entities.EventType.CREATE_LOADBALANCER;
import static org.openstack.atlas.service.domain.events.entities.EventType.DELETE_LOADBALANCER;

public class SyncListener extends BaseListener {

    final Log LOG = LogFactory.getLog(SyncListener.class);

    @Override
    public void doOnMessage(Message message) throws Exception {
        LOG.debug("Entering " + getClass());
        LOG.debug(message);
        Sync queueSyncObject = getEsbRequestFromMessage(message).getSyncObject();
        LoadBalancer dbLoadBalancer;
        LoadBalancerStatus curStatus;

        try {
            dbLoadBalancer = loadBalancerService.get(queueSyncObject.getLoadBalancerId(), queueSyncObject.getAccountId());
        } catch (EntityNotFoundException enfe) {
            LOG.error(String.format("EntityNotFoundException thrown while attempting to sync Loadbalancer #%d: ", queueSyncObject.getLoadBalancerId()));
            return;
        }

        if (queueSyncObject.getLocationToSyncFrom().equals(SyncLocation.DATABASE)) {
            LOG.debug(String.format("Synchronizing load balancer #%d with database configuration", queueSyncObject.getLoadBalancerId()));

            final LoadBalancerStatus loadBalancerStatus = dbLoadBalancer.getStatus();

            List<SnmpUsage> usages = new ArrayList<SnmpUsage>();
            try {
                LOG.info(String.format("Collecting DELETE_LOADBALANCER usage for load balancer %s...", dbLoadBalancer.getId()));
                usages = usageEventCollection.getUsage(dbLoadBalancer);
                LOG.info(String.format("Successfully collected DELETE_LOADBALANCER usage for load balancer %s", dbLoadBalancer.getId()));
            } catch (UsageEventCollectionException e) {
                LOG.error(String.format("Collection of the DELETE_LOADBALANCER usage event failed for " +
                        "load balancer: %s :: Exception: %s", dbLoadBalancer.getId(), e));
            }

            try {
                if (!isRestAdapter()) {
//                    LOG.debug(String.format("Removing loadbalancer for sync in STM for LB: %s", dbLoadBalancer.getId()));
//                    reverseProxyLoadBalancerStmService.deleteLoadBalancer(dbLoadBalancer);
//                    LOG.debug(String.format("Successfully removed loadbalancer for sync in STM for LB: %s", dbLoadBalancer.getId()));
//                } else {
                    LOG.debug(String.format("Removing loadbalancer for sync in ZXTM for LB: %s", dbLoadBalancer.getId()));
                    reverseProxyLoadBalancerService.deleteLoadBalancer(dbLoadBalancer);
                    LOG.debug(String.format("Successfully removed loadbalancer for sync in ZXTM for LB: %s", dbLoadBalancer.getId()));
                }
            } catch (Exception e) {
                String msg = String.format("Error deleting loadbalancer #%d in SyncListener(): ", queueSyncObject.getLoadBalancerId());
                loadBalancerService.setStatus(dbLoadBalancer, ERROR);
                notificationService.saveAlert(dbLoadBalancer.getAccountId(), dbLoadBalancer.getId(), e, AlertType.ZEUS_FAILURE.name(), msg);
                LOG.error(msg, e);
            }

            if (loadBalancerStatus.equals(PENDING_DELETE) || loadBalancerStatus.equals(DELETED)) {
                loadBalancerService.setStatus(dbLoadBalancer, DELETED);

                loadBalancerService.pseudoDelete(dbLoadBalancer);

                if (loadBalancerStatus.equals(PENDING_DELETE)) {
                    // Add atom entry
                    String atomTitle = "Load Balancer Successfully Deleted";
                    String atomSummary = "Load balancer successfully deleted";
                    notificationService.saveLoadBalancerEvent(dbLoadBalancer.getUserName(), dbLoadBalancer.getAccountId(), dbLoadBalancer.getId(), atomTitle, atomSummary, DELETE_LOADBALANCER, DELETE, INFO);

                    // Notify usage processor
                    Calendar eventTime = Calendar.getInstance();
                    LOG.info(String.format("Processing DELETE_LOADBALANCER usage for load balancer %s...", dbLoadBalancer.getId()));
                    usageEventCollection.processUsageEvent(usages, dbLoadBalancer, UsageEvent.DELETE_LOADBALANCER, eventTime);
                    LOG.info(String.format("Completed processing DELETE_LOADBALANCER usage for load balancer %s", dbLoadBalancer.getId()));

                    //Set status record
                    loadBalancerStatusHistoryService.save(dbLoadBalancer.getAccountId(), dbLoadBalancer.getId(), LoadBalancerStatus.PENDING_DELETE);
                }
            } else {

                //First recreate the original virtual server...
                try {
                    LoadBalancer tempLb = loadBalancerService.getWithUserPages(queueSyncObject.getLoadBalancerId(), queueSyncObject.getAccountId());
                    tempLb.setSslTermination(null);

                    LOG.debug(String.format("Syncing load balancer %s setting status to PENDING_UPDATE", tempLb.getId()));
                    loadBalancerService.setStatus(dbLoadBalancer, PENDING_UPDATE);

                    loadBalancerStatusHistoryService.save(dbLoadBalancer.getAccountId(), dbLoadBalancer.getId(), LoadBalancerStatus.PENDING_UPDATE);
                    if (isRestAdapter()) {
                        LOG.debug(String.format("Updating loadbalancer: %s in STM...", tempLb.getId()));
                        reverseProxyLoadBalancerStmService.updateLoadBalancer(tempLb, tempLb, loadBalancerService.getUserPages(tempLb.getId(), tempLb.getAccountId()));
                        LOG.debug(String.format("Successfully Updated loadbalancer: %s in STM...", tempLb.getId()));
                    } else {
                        LOG.debug(String.format("Re-creating loadbalancer: %s in ZXTM...", tempLb.getId()));
                        reverseProxyLoadBalancerService.createLoadBalancer(tempLb);
                        LOG.debug(String.format("Successfully Re-created loadbalancer: %s in ZXTM...", tempLb.getId()));
                    }

                    LOG.debug(String.format("Sync of load balancer %s complete, updating status and saving events and usage...", tempLb.getId()));
                    loadBalancerService.setStatus(dbLoadBalancer, ACTIVE);

                    if (loadBalancerStatus.equals(BUILD)) {
                        NodesHelper.setNodesToStatus(dbLoadBalancer, ONLINE);
                        dbLoadBalancer.setStatus(ACTIVE);
                        dbLoadBalancer = loadBalancerService.update(dbLoadBalancer);

                        //Set status record
                        loadBalancerStatusHistoryService.save(dbLoadBalancer.getAccountId(), dbLoadBalancer.getId(), LoadBalancerStatus.ACTIVE);

                        // Add atom entry
                        String atomTitle = "Load Balancer Successfully Created";
                        String atomSummary = createAtomSummary(dbLoadBalancer).toString();
                        notificationService.saveLoadBalancerEvent(dbLoadBalancer.getUserName(), dbLoadBalancer.getAccountId(), dbLoadBalancer.getId(), atomTitle, atomSummary, CREATE_LOADBALANCER, CREATE, INFO);

                        // Notify old usage processor
                        Calendar eventTime = Calendar.getInstance();

                        try {
                            usageEventCollection.processZeroUsageEvent(dbLoadBalancer, UsageEvent.CREATE_LOADBALANCER, eventTime);
                        } catch (UsageEventCollectionException uex) {
                            LOG.error(String.format("Collection and processing of the usage event failed for load balancer: %s " +
                                    ":: Exception: %s", dbLoadBalancer.getId(), uex));
                        }
                    }
                } catch (Exception e) {
                    String msg = String.format("Error re-creating loadbalancer #%d in SyncListener(), " +
                            "setting status to ERROR, original status: %s:", queueSyncObject.getLoadBalancerId(), loadBalancerStatus);

                    loadBalancerService.setStatus(dbLoadBalancer, ERROR);
                    notificationService.saveAlert(dbLoadBalancer.getAccountId(), dbLoadBalancer.getId(), e, AlertType.ZEUS_FAILURE.name(), msg);
                    LOG.error(msg, e);
                }

                try {
                    //Now create the secure VS if ssl termination was already on the loadbalancer...
                    if (dbLoadBalancer.hasSsl()) {
                        org.openstack.atlas.service.domain.entities.SslTermination dbTermination = sslTerminationService.getSslTermination(dbLoadBalancer.getId(), dbLoadBalancer.getAccountId());

                        //Map
                        SslTermination domainSslTermination = new SslTermination();
                        domainSslTermination.setIntermediateCertificate(dbTermination.getIntermediateCertificate());
                        domainSslTermination.setCertificate(dbTermination.getCertificate());
                        domainSslTermination.setPrivatekey(dbTermination.getPrivatekey());
                        domainSslTermination.setEnabled(dbTermination.isEnabled());
                        domainSslTermination.setSecurePort(dbTermination.getSecurePort());
                        domainSslTermination.setSecureTrafficOnly(dbTermination.isSecureTrafficOnly());

                        LOG.debug(String.format("Syncing SSL-Termination for load balancer %s", dbLoadBalancer.getId()));
//                        loadBalancerService.setStatus(dbLoadBalancer, PENDING_UPDATE);

                        //We must re-validate cert/keys before sending to zeus  V1-D-04287
                        ZeusSslTermination zeusTermination = sslTerminationService.updateSslTermination(dbLoadBalancer.getId(), dbLoadBalancer.getAccountId(), domainSslTermination);

                        if (isRestAdapter()) {
                            LOG.debug(String.format("Updating ssl termination for load balancer: %s in STM", dbLoadBalancer.getId()));
                            reverseProxyLoadBalancerStmService.updateSslTermination(dbLoadBalancer, zeusTermination ,loadBalancerService.getUserPages(dbLoadBalancer.getId(), dbLoadBalancer.getAccountId()));
                            LOG.debug(String.format("Successfully updated ssl termination for load balancer: %s in STM", dbLoadBalancer.getId()));
                        } else {
                            LOG.debug(String.format("Updating ssl termination for load balancer: %s in ZXTM", dbLoadBalancer.getId()));
                            reverseProxyLoadBalancerService.updateSslTermination(dbLoadBalancer, zeusTermination);
                            LOG.debug(String.format("Successfully updated ssl termination for load balancer: %s in ZXTM", dbLoadBalancer.getId()));
                        }

                        LOG.debug(String.format("Sync for SSL-Termination load balancer %s complete.", dbLoadBalancer.getId()));
                        loadBalancerService.setStatus(dbLoadBalancer, ACTIVE);
                        loadBalancerStatusHistoryService.save(dbLoadBalancer.getAccountId(), dbLoadBalancer.getId(), LoadBalancerStatus.ACTIVE);

                        if (loadBalancerStatus.equals(PENDING_UPDATE) || loadBalancerStatus.equals(ERROR)) {
                            Calendar eventTime = Calendar.getInstance();

                            if (dbLoadBalancer.isUsingSsl()) {
                                if (dbLoadBalancer.getSslTermination().isSecureTrafficOnly()) {
                                    usageEventCollection.collectUsageAndProcessUsageRecords(dbLoadBalancer, UsageEvent.SSL_ONLY_ON, eventTime);
                                } else {
                                    usageEventCollection.collectUsageAndProcessUsageRecords(dbLoadBalancer, UsageEvent.SSL_MIXED_ON, eventTime);
                                }
                            } else {
                                usageEventCollection.collectUsageAndProcessUsageRecords(dbLoadBalancer, UsageEvent.SSL_OFF, eventTime);
                            }
                        }
                    }
                } catch (Exception e) {
                    String msg = String.format("Error re-creating ssl terminated loadbalancer #%d in SyncListener(), " +
                            "setting status to ERROR, original status: :", queueSyncObject.getLoadBalancerId(), loadBalancerStatus);
                    loadBalancerService.setStatus(dbLoadBalancer, ERROR);
                    LOG.error(msg, e);
                }
            }
        } else if (queueSyncObject.getLocationToSyncFrom().equals(SyncLocation.ZEUS)) {
            LOG.warn(String.format("Load balancers can only be synchronized with the database at this time. Warning Loadbalancer #%d wasn't actually synced", queueSyncObject.getLoadBalancerId()));
        }

        LOG.info(String.format("Sync operation complete for loadbalancer #%d ", queueSyncObject.getLoadBalancerId()));
    }

    private StringBuilder createAtomSummary(LoadBalancer lb) {
        StringBuilder atomSummary = new StringBuilder();
        atomSummary.append("Load balancer successfully created with ");
        atomSummary.append("name: '").append(lb.getName()).append("', ");
        atomSummary.append("algorithm: '").append(lb.getAlgorithm()).append("', ");
        atomSummary.append("protocol: '").append(lb.getProtocol()).append("', ");
        atomSummary.append("port: '").append(lb.getPort()).append("'");
        return atomSummary;
    }
}

package org.openstack.atlas.usagerefactor.collection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.service.domain.entities.Host;
import org.openstack.atlas.service.domain.entities.LoadBalancer;
import org.openstack.atlas.service.domain.events.UsageEvent;
import org.openstack.atlas.service.domain.exceptions.UsageEventCollectionException;
import org.openstack.atlas.usagerefactor.SnmpUsage;
import org.openstack.atlas.usagerefactor.processor.UsageEventProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class UsageEventCollection extends AbstractUsageEventCollection {
    private final Log LOG = LogFactory.getLog(UsageEventCollection.class);
    private List<Future<SnmpUsage>> futures;

    public UsageEventCollection() throws UsageEventCollectionException {
    }

    public UsageEventCollection(List<Future<SnmpUsage>> futures) throws UsageEventCollectionException  {
        this.futures = futures;
    }

    @Override
    public List<Future<SnmpUsage>> collectUsageRecords(ExecutorService executorService,
                                                       UsageEventProcessor usageEventProcessor, List<Host> hosts,
                                                       LoadBalancer lb, UsageEvent event)
            throws UsageEventCollectionException {

        LOG.debug("Collecting SNMP Usages for load balancer: " + lb.getId());

        List<Callable<SnmpUsage>> callables = new ArrayList<Callable<SnmpUsage>>();
        for (Host h : hosts) {
            callables.add(new SnmpVSCollector(h, lb));
        }
        try {
            LOG.debug("Executing SNMP collection tasks for loadbalancer: " + lb.getId());
            this.futures = executorService.invokeAll(callables);
        } catch (InterruptedException e) {
            LOG.error("Error Executing SNMP: " + e);
            throw new UsageEventCollectionException("Error executing SNMP collection: ", e);
        }
        return this.futures;
    }

    @Override
    public void processFutures(List<Future<SnmpUsage>> futures, UsageEventProcessor usageEventProcessor, LoadBalancer lb, UsageEvent event) throws UsageEventCollectionException {
        List<SnmpUsage> usages = new ArrayList<SnmpUsage>();
        if (futures != null) {
            this.futures = futures;
        }
        for (Future<SnmpUsage> f : this.futures) {
            try {
                usages.add(f.get());
            } catch (InterruptedException e) {
                LOG.error("Error retrieving SNMP futures: " + e);
                throw new UsageEventCollectionException("Error retrieving SNMP futures: ", e);
            } catch (ExecutionException e) {
                LOG.error("Error retrieving SNMP futures: " + e);
                throw new UsageEventCollectionException("Error retrieving SNMP futures: ", e);
            }
        }
        usageEventProcessor.processUsageEvent(usages, lb, event);
    }

    public List<Future<SnmpUsage>> getFutures() {
        return this.futures;
    }
}

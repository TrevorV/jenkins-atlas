package org.openstack.atlas.service.domain.services;


import org.openstack.atlas.service.domain.entities.LoadBalancerStatus;
import org.openstack.atlas.service.domain.entities.LoadBalancerStatusHistory;

public interface LoadBalancerStatusHistoryService {

    public LoadBalancerStatusHistory save(LoadBalancerStatusHistory loadBalancerStatusHistory);

    public LoadBalancerStatusHistory save(Integer accountId, Integer loadBalancerId, LoadBalancerStatus loadBalancerStatus);

}

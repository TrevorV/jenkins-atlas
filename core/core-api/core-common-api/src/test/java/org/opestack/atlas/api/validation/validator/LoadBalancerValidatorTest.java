package org.opestack.atlas.api.validation.validator;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.openstack.atlas.api.validation.result.ValidatorResult;
import org.openstack.atlas.api.validation.validator.LoadBalancerValidator;
import org.openstack.atlas.api.validation.validator.builder.LoadBalancerValidatorBuilder;
import org.openstack.atlas.api.validation.validator.builder.NodeValidatorBuilder;
import org.openstack.atlas.core.api.v1.*;
import org.openstack.atlas.datamodel.CoreAlgorithmType;
import org.openstack.atlas.datamodel.CoreHealthMonitorType;
import org.openstack.atlas.datamodel.CoreNodeCondition;
import org.openstack.atlas.datamodel.CorePersistenceType;
import org.openstack.atlas.service.domain.stub.LoadBalancerStubFactory;

import java.util.GregorianCalendar;

import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.openstack.atlas.api.validation.context.HttpRequestType.POST;
import static org.openstack.atlas.api.validation.context.HttpRequestType.PUT;

@RunWith(Enclosed.class)
public class LoadBalancerValidatorTest {

    public static class whenValidatingPost {

        private LoadBalancerValidator validator;
        private LoadBalancer loadBalancer;

        @Before
        public void setUp() {
            loadBalancer = LoadBalancerStubFactory.createMinimalDataModelLoadBalancerForPost();
            validator = new LoadBalancerValidator(
                    new LoadBalancerValidatorBuilder(
                            new CoreAlgorithmType(),
                            new NodeValidatorBuilder(new CoreNodeCondition())));
        }

        @Test
        public void shouldReturnTrueWhenGivenAValidMinimalLoadBalancer() {
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldReturnTrueWhenGivenAValidFullyHydratedLoadBalancer() {
            loadBalancer = LoadBalancerStubFactory.createHydratedDataModelLoadBalancerForPost();
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldRejectIdForVipIfTypeIsSet() {
            loadBalancer = LoadBalancerStubFactory.createHydratedDataModelLoadBalancerForPost();
            loadBalancer.getVirtualIps().get(0).setId(1234);

            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldAcceptIdOnly() {
            loadBalancer.getVirtualIps().clear();
            VirtualIp vipper = new VirtualIp();
            vipper.setId(23);
            loadBalancer.getVirtualIps().add(vipper);

            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldRejectTypeIfIdIsSet() {
            loadBalancer = new LoadBalancer();
            VirtualIp vipper = new VirtualIp();
            vipper.setId(23);
            vipper.setType(VipType.PRIVATE);
            loadBalancer.getVirtualIps().add(vipper);

            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectMultipleVips() {
            loadBalancer.getVirtualIps().clear();

            VirtualIp vip = new VirtualIp();
            vip.setId(23);
            vip.setType(VipType.PRIVATE);
            VirtualIp vip2 = new VirtualIp();
            vip.setId(43);
            vip.setType(VipType.PRIVATE);

            loadBalancer.getVirtualIps().add(vip);
            loadBalancer.getVirtualIps().add(vip2);

            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());

        }

        @Test
        public void shouldFailWhenGivenAnInvalidHTTPHealthMonitor() {
            HealthMonitor monitor = new HealthMonitor();
            monitor.setType(CoreHealthMonitorType.HTTP);
            monitor.setDelay(10);
            monitor.setTimeout(60);
            monitor.setAttemptsBeforeDeactivation(3);
            loadBalancer.setHealthMonitor(monitor);

            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldFailWhenGivenAnInvalidConnectHealthMonitor() {
            HealthMonitor monitor = new HealthMonitor();
            monitor.setType(CoreHealthMonitorType.CONNECT);
            loadBalancer.setHealthMonitor(monitor);

            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldNotAcceptRandomAlgorithm() {
            loadBalancer.setAlgorithm("RANDOM");
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldAcceptMinAndMaxPorts() {
            loadBalancer.setPort(1);
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertTrue(result.passedValidation());

            loadBalancer.setPort(65535);
            result = validator.validate(loadBalancer, POST);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldRejectInvalidPorts() {
            loadBalancer.setPort(0);
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());

            loadBalancer.setPort(65536);
            result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldAcceptSessionPersistence() {
            SessionPersistence persistence = new SessionPersistence();
            persistence.setPersistenceType(CorePersistenceType.HTTP_COOKIE);
            loadBalancer.setSessionPersistence(persistence);

            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldAcceptConnectionLimits() {
            ConnectionThrottle throttle = new ConnectionThrottle();
            throttle.setMaxRequestRate(60);
            throttle.setRateInterval(45);
            loadBalancer.setConnectionThrottle(throttle);

            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldRejectWhenMissingNodes() {
            loadBalancer.getNodes().clear();
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectId() {
            loadBalancer.setId(Integer.SIZE);
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectStatus() {
            loadBalancer.setStatus("BUILD");
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectCluster() {
            loadBalancer.setCluster(new Cluster());
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectCreated() {
            Created created = new Created();
            created.setTime(new GregorianCalendar());
            loadBalancer.setCreated(created);
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectUpdated() {
            Updated updated = new Updated();
            updated.setTime(new GregorianCalendar());
            loadBalancer.setUpdated(updated);
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectEmptyName() {
            loadBalancer.setName(null);
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldHaveSameErrorsForMultipleValidations() {
            ValidatorResult result = validator.validate(new LoadBalancer(),
                    POST);
            int numMessagesFirstPass = result.getValidationErrorMessages()
                    .size();
            result = validator.validate(new LoadBalancer(), POST);
            int numMessagesSecondPass = result.getValidationErrorMessages()
                    .size();
            assertEquals(numMessagesFirstPass, numMessagesSecondPass);
        }

        @Test
        public void shouldRejectIdWhenVipTypeIsSet() {
            VirtualIp vip = new VirtualIp();
            vip.setId(1234);
            vip.setType(VipType.PUBLIC);
            loadBalancer.getVirtualIps().clear();
            loadBalancer.getVirtualIps().add(vip);

            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectWhenDuplicateNodesDetected() {
            Node node = new Node();
            node.setAddress("10.1.1.1");
            node.setPort(80);
            node.setCondition(CoreNodeCondition.ENABLED);
            loadBalancer.getNodes().add(node);

            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectNameLongerThan128Characters() {
            String longName = generateStringWithLength(129);
            loadBalancer.setName(longName);
            ValidatorResult result = validator.validate(loadBalancer, POST);
            assertFalse(result.passedValidation());
        }
    }

    public static class whenValidatingPut {
        private LoadBalancerValidator validator;
        private LoadBalancer loadBalancer;

        @Before
        public void setUpValidator() {
            validator = new LoadBalancerValidator(
                    new LoadBalancerValidatorBuilder(
                            new CoreAlgorithmType(),
                            new NodeValidatorBuilder(new CoreNodeCondition())));
        }

        @Before
        public void setupValidLoadBalancerObject() {
            loadBalancer = new LoadBalancer();
            loadBalancer.setName("an-updated-loadbalancer-name");
            loadBalancer.setProtocol("FTP");
            loadBalancer.setPort(800);
            loadBalancer.setAlgorithm(CoreAlgorithmType.LEAST_CONNECTIONS);
        }

        @Test
        public void shouldFailWhenNoAttributesToUpdate() {
            ValidatorResult result = validator.validate(new LoadBalancer(), PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectWhenNodesIsNotEmpty() {
            loadBalancer.getNodes().add(new Node());
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectWhenVirtualIpsIsNotEmpty() {
            loadBalancer.getVirtualIps().add(new VirtualIp());
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectId() {
            loadBalancer.setId(1234);
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectStatus() {
            loadBalancer.setStatus("BUILD");
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectCluster() {
            loadBalancer.setCluster(new Cluster());
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectCreated() {
            Created created = new Created();
            created.setTime(new GregorianCalendar());
            loadBalancer.setCreated(created);
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectUpdated() {
            Updated updated = new Updated();
            updated.setTime(new GregorianCalendar());
            loadBalancer.setUpdated(updated);
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectSessionPersistence() {
            SessionPersistence persistence = new SessionPersistence();
            persistence.setPersistenceType(CorePersistenceType.HTTP_COOKIE);
            loadBalancer.setSessionPersistence(persistence);

            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectConnectionLimits() {
            ConnectionThrottle throttle = new ConnectionThrottle();
            throttle.setMaxRequestRate(60);
            throttle.setRateInterval(45);
            loadBalancer.setConnectionThrottle(throttle);

            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldRejectConnectionLogging() {
            ConnectionLogging connectionLogging = new ConnectionLogging();
            connectionLogging.setEnabled(true);
            loadBalancer.setConnectionLogging(connectionLogging);
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldAcceptOnlyPort() {
            loadBalancer = new LoadBalancer();
            loadBalancer.setPort(80);
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldAcceptOnlyAlgorithm() {
            loadBalancer = new LoadBalancer();
            loadBalancer.setAlgorithm(CoreAlgorithmType.ROUND_ROBIN);
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldAcceptOnlyProtocol() {
            loadBalancer = new LoadBalancer();
            loadBalancer.setProtocol("HTTP");
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldAcceptOnlyName() {
            loadBalancer = new LoadBalancer();
            loadBalancer.setName("Biased load BALANCER. Ha!");
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldAcceptCoreAlgorithms() {
            loadBalancer = new LoadBalancer();

            for (String coreAlgorithm : CoreAlgorithmType.values()) {
                loadBalancer.setAlgorithm(coreAlgorithm);
                ValidatorResult result = validator.validate(loadBalancer, PUT);
                assertTrue(result.passedValidation());
            }
        }

        @Test
        public void shouldNotAcceptRandomAlgorithm() {
            loadBalancer = new LoadBalancer();
            loadBalancer.setAlgorithm("RANDOM");
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

        @Test
        public void shouldAcceptNameWith128Characters() {
            String longName = generateStringWithLength(128);
            loadBalancer.setName(longName);
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertTrue(result.passedValidation());
        }

        @Test
        public void shouldRejectNameLongerThan128Characters() {
            String longName = generateStringWithLength(129);
            loadBalancer.setName(longName);
            ValidatorResult result = validator.validate(loadBalancer, PUT);
            assertFalse(result.passedValidation());
        }

    }

    private static String generateStringWithLength(int length) {
        String string = "";
        for (int i = 0; i < length; i++) {
            string += "a";
        }
        return string;
    }
}

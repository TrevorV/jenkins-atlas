package org.openstack.atlas.atom.client;

import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.atom.config.AtomHopperConfiguration;
import org.openstack.atlas.atom.config.AtomHopperConfigurationKeys;
import org.openstack.atlas.atom.handler.AHUSLClientHandler;
import org.openstack.atlas.atom.util.AHUSLUtil;
import org.openstack.atlas.cfg.Configuration;
import ru.hh.jersey.hchttpclient.ApacheHttpClient;

import javax.ws.rs.core.MediaType;

public class AHUSLClient {
    private final Log LOG = LogFactory.getLog(AHUSLClient.class);
    private static Configuration configuration = new AtomHopperConfiguration();


    private String endPoint;
    private static final String TOKEN_HEADER = "X-AUTH-TOKEN" ;
    private ApacheHttpClient client;

    public AHUSLClient(String endPoint, ApacheHttpClient client) {
        this.endPoint = endPoint;
        if (endPoint == null) {
            this.endPoint = configuration.getString(AtomHopperConfigurationKeys.atom_hopper_endpoint);
        }
        this.client = client;
    }

    /**
     * This method will create the clients web resource based on the atom_hopper_endpoint
     * provided.
     *
     * @throws Exception
     */
    public AHUSLClient() throws Exception {
        this(configuration.getString(AtomHopperConfigurationKeys.atom_hopper_endpoint), AHUSLClientHandler.createHttpClient());
    }


    /**
     * This method destroys the client
     */
    public void destroy() {
        this.client.destroy();
    }

    /**
     * This method will take an xml bean based object and POST it to the specified endpoint of the Atom-Hopper service
     *
     * @param entry the object to post
     * @return the ClientResponse
     */
    public ClientResponse postEntry(Object entry) throws Exception {
        ClientResponse response = null;
        try {
            response = client.resource(endPoint)
                    .accept(MediaType.APPLICATION_XML)
                    .type(MediaType.APPLICATION_ATOM_XML)
                    .post(ClientResponse.class, entry);
        } catch (ClientHandlerException cpe) {
            throw new ClientHandlerException(AHUSLUtil.getStackTrace(cpe));
        } catch (Exception ex) {
            throw new Exception(AHUSLUtil.getStackTrace(ex));
        }
        return response;
    }

    /**
     * This method will take an xml bean based object and POST it to the specified endpoint of the Atom-Hopper service using
     * X-AUTH-TOKEN Header to identify the service..
     *
     * @param entry the object to post
     * @return the ClientResponse
     */
    public ClientResponse postEntryWithToken(Object entry, String token) throws Exception {
        ClientResponse response = null;
        try {
            response = client.resource(endPoint)
                    .accept(MediaType.APPLICATION_XML)
                    .header(TOKEN_HEADER, token)
                    .type(MediaType.APPLICATION_ATOM_XML)
                    .post(ClientResponse.class, entry);
        } catch (ClientHandlerException cpe) {
            throw new ClientHandlerException(AHUSLUtil.getStackTrace(cpe));
        } catch (Exception ex) {
            throw new Exception(AHUSLUtil.getStackTrace(ex));
        }
        return response;
    }
}
/* Copyright 2018 T-Mobile US, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tmobile.opensource.casquatch;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PreDestroy;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.JdkSSLOptions;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.RemoteEndpointAwareJdkSSLOptions;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import com.datastax.driver.core.policies.ConstantSpeculativeExecutionPolicy;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.ExponentialReconnectionPolicy;
import com.datastax.driver.core.policies.FallthroughRetryPolicy;
import com.datastax.driver.core.policies.LoadBalancingPolicy;
import com.datastax.driver.core.policies.RetryPolicy;
import com.datastax.driver.core.policies.SpeculativeExecutionPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.datastax.driver.mapping.Mapper;
import com.datastax.driver.mapping.MappingManager;
import com.datastax.driver.mapping.annotations.Table;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import com.tmobile.opensource.casquatch.exceptions.DriverException;
import com.tmobile.opensource.casquatch.models.AbstractCassandraTable;
import com.tmobile.opensource.casquatch.models.shared.DriverConfig;

/**
 * This object provides a standard interface for connecting to Cassandra clusters within SDE.
 *
 * @version 1.0
 * @since   2018-02-26
 */
public class CassandraDriver {

	public static class Builder {
		private class Configuration {		
			class SpeculativeExecution {
				int delay;
				int executions;
			}	
			class Defaults {
				String clusterType;
				String consistencyLevel;		
				String solrDC;
				boolean saveNulls;
			}
			class Features {
				boolean driverConfig;
				boolean solr;
			}
			class Connections {
				class Limit {
					int min;
					int max;
				}
				Limit local = new Limit();
				Limit remote = new Limit();
			}	
			class Timeout {
				int read;
				int connection;
			}	
			class Reconnection {
				int delay;
				int maxDelay;			
			}	
			class SSL {
				class Keystore {
					String path;
					String password;
				}
				boolean node;
				Keystore truststore = new Keystore();
			}
			Connections connections = new Connections();
			Timeout timeout = new Timeout();
			Reconnection reconnection = new Reconnection();
			SpeculativeExecution speculativeExecution = new SpeculativeExecution();
			Defaults defaults = new Defaults();
			Features features = new Features();
			SSL ssl = new SSL();
			
			String username;
			String password;						
			String localDC;			
			String keyspace;			
			int port;			
			String contactPoints;	
			int useRemoteConnections;
			
			public Configuration() {
				this.contactPoints = "localhost";
				this.port=9042;
				this.username = "cassandra";
				this.password = "cassandra";			
				this.defaults.consistencyLevel = "LOCAL_QUORUM";
				this.defaults.clusterType = "HA";
				this.useRemoteConnections= 2;			
				this.connections.local.min=1;
				this.connections.local.max=3;
				this.connections.remote.min=1;
				this.connections.remote.max=1;
				this.speculativeExecution.delay=500;
				this.speculativeExecution.executions=2;
				this.timeout.read=500;
				this.timeout.connection=12000;
				this.reconnection.delay=500;
				this.reconnection.maxDelay=300000;
				this.features.driverConfig=true;
				this.features.solr=true;
				this.defaults.solrDC="search";
				this.defaults.saveNulls=false;
				this.ssl.node=false;
			}
			
			public String toString() {
				try {
					ObjectMapper mapper = new ObjectMapper();
					mapper.setVisibility(PropertyAccessor.FIELD,Visibility.ANY);
					return mapper.writeValueAsString(this).replace(this.password, "MASKED");
				} catch (JsonProcessingException e) {
					logger.debug("Failed to serialize config",e);
					return "Failed to serialize";
				}
			}
			
			public boolean validate() throws DriverException {
		    	logger.debug("Configuration Validation: "+this.toString());
				
		    	if(this.contactPoints == null || this.contactPoints.isEmpty())
		    		throw new DriverException(401,"Contact Points are required");
		    		
		    	if(this.keyspace == null || this.keyspace.isEmpty())
		    		throw new DriverException(401,"Keyspace is required");
		    	
		    	if(this.localDC == null || this.localDC.isEmpty())
		    		throw new DriverException(401,"Local DC is required");
		    	
		    	
		    	return true;
				
			}
		}
		
		private Builder.Configuration config;
		
	    /**
	     * CassandraDriver Builder constructor. Configures default settings.
	     */
		public Builder() {
			config = new Builder.Configuration();		
		}
		
	    /**
	     * Build with username
	     * @param username connection username
	     * @return Reference to Builder object
	     */
		public Builder withUsername(String username) {
			config.username = username;
			return this;
		}
		
	    /**
	     * Build with password
	     * @param password connection password
	     * @return Reference to Builder object
	     */
		public Builder withPassword(String password) {
			config.password = password;
			return this;
		}
		
	    /**
	     * Build with local datacenter
	     * @param localDC local data center
	     * @return Reference to Builder object
	     */
		public Builder withLocalDC(String localDC) {
			config.localDC = localDC;
			return this;
		}	
		
	    /**
	     * Build with port
	     * @param port connection port
	     * @return Reference to Builder object
	     */
		public Builder withPort(int port) {
			config.port=port;
			return this;
		}	
		
	    /**
	     * Build with keyspace
	     * @param keyspace connection keyspace
	     * @return Reference to Builder object
	     */
		public Builder withKeyspace(String keyspace) {
			config.keyspace=keyspace;
			return this;
		}	
		
	    /**
	     * Build with comma separated list of contact points
	     * @param contactPoints connection contact points
	     * @return Reference to Builder object
	     */
		public Builder withContactPoints(String contactPoints) {
			config.contactPoints = contactPoints;
			return this;
		}
		
	    /**
	     * Build with default consistency level
	     * @param defaultConsistencyLevel default consistency level
	     * @return Reference to Builder object
	     */
		public Builder withDefaultConsistencyLevel(String defaultConsistencyLevel) {
			config.defaults.consistencyLevel = defaultConsistencyLevel;
			return this;
		}
		
	    /**
	     * Build with configured remote connections per query
	     * @param remoteConnections remote connection count
	     * @return Reference to Builder object
	     */
		public Builder withUseRemoteConnections(int remoteConnections) {
			config.useRemoteConnections= remoteConnections;
			return this;
		}
		
	    /**
	     * Build with local connection limits
	     * @param min min connections
	     * @param max max connections
	     * @return Reference to Builder object
	     */
		public Builder withLocalConnectionLimit(int min, int max) {
			config.connections.local.min = min;
			config.connections.local.max = max;
			return this;
		}
		
	    /**
	     * Build with remote connection limits
	     * @param min min connections
	     * @param max max connections
	     * @return Reference to Builder object
	     */
		public Builder withRemoteConnectionLimit(int min, int max) {
			config.connections.remote.min = min;
			config.connections.remote.max = max;
			return this;
		}
		
	    /**
	     * Build with speculative execution.
	     * @param delay number of ms to wait before triggering
	     * @param executions max number of executions
	     * @return Reference to Builder object
	     */
		public Builder withSpeculativeExecution(int delay, int executions) {
			config.speculativeExecution.delay = delay;
			config.speculativeExecution.executions = executions;
			return this;
		}
		
	    /**
	     * Build with reconnection
	     * @param delay number of ms to wait before reconnecting
	     * @param maxDelay maximum number of ms to wait
	     * @return Reference to Builder object
	     */
		public Builder withReconnection(int delay, int maxDelay) {
			config.reconnection.delay = delay;
			config.reconnection.maxDelay = maxDelay;
			return this;
		}
		
	    /**
	     * Build with read timeout
	     * @param readTimeout timeout in ms
	     * @return Reference to Builder object
	     */
		public Builder withReadTimeout(int readTimeout) {
			config.timeout.read = readTimeout;
			return this;
		}
		
	    /**
	     * Build with connection timeout
	     * @param connectionTimeout timeout in ms
	     * @return Reference to Builder object
	     */
		public Builder withConnectionTimeout(int connectionTimeout) {
			config.timeout.connection = connectionTimeout;
			return this;
		}
		
	    /**
	     * Build with a single data center
	     * @return Reference to Builder object
	     */
		public Builder withSingleDCCluster() {
			config.defaults.clusterType = "Single" ;
			return this;
		}
		
	    /**
	     * Build with a highly available cluster (multiple data centers)
	     * @return Reference to Builder object
	     */
		public Builder withHACluster() {
			config.defaults.clusterType = "HA" ;
			return this;
		}
		
	    /**
	     * Build with driver configuration table
	     * @return Reference to Builder object
	     */
		public Builder withDriverConfig() {
			config.features.driverConfig=true;
			return this;
		}
		
	    /**
	     * Build without driver configuration table
	     * @return Reference to Builder object
	     */
		public Builder withoutDriverConfig() {
			config.features.driverConfig=false;
			return this;
		}
		
	    /**
	     * Build with solr enabled
	     * @return Reference to Builder object
	     */
		public Builder withSolr() {
			config.features.solr=true;
			return this;
		}
		
	    /**
	     * Build without solr enabled
	     * @return Reference to Builder object
	     */
		public Builder withoutSolr() {
			config.features.solr=false;
			return this;
		}
		
	    /**
	     * Build with solr data center
	     * @param dc datacenter for solr
	     * @return Reference to Builder object
	     */
		public Builder withSolrDC(String dc) {
			config.defaults.solrDC=dc;
			return this;
		}
		
	    /**
	     * Build without saving nulls
	     * @return Reference to Builder object
	     */
		public Builder withSaveNulls() {
			config.defaults.saveNulls=true;
			return this;
		}
		
	    /**
	     * Build with saving nulls
	     * @return Reference to Builder object
	     */
		public Builder withoutSaveNulls() {
			config.defaults.saveNulls=false;
			return this;
		}
		
	    /**
	     * Build with ssl
	     * @return Reference to Builder object
	     */
		public Builder withSSL() {
			config.ssl.node=true;
			return this;
		}
		
	    /**
	     * Build without ssl
	     * @return Reference to Builder object
	     */
		public Builder withoutSSL() {
			config.ssl.node=false;
			return this;
		}
		
	    /**
	     * BBuild with defined truststore
	     * @param path path to truststore
	     * @param password truststore password
	     * @return Reference to Builder object
	     */
		public Builder withTrustStore(String path, String password) {
			config.ssl.truststore.path=path;
			config.ssl.truststore.password=password;
			return this;
		}

		
	    /**
	     * Get configuration object
	     * @return config Configuration instance
	     */	
		private Builder.Configuration getConfiguration() {
			return config;
		}
		
	    /**
	     * Build the defined CassandraDriver
	     * @return CassandraDriver Configured driver object
	     */
		public CassandraDriver build() {
			return CassandraDriver.buildFrom(getConfiguration());
		}
	}

    private Map<String,Cluster> clusterMap;
    private Map<String,Session> sessionMap;
    private Map<String,MappingManager> mappingManagerMap;
    private DatabaseCache<DriverConfig> driverConfig;

    protected Builder.Configuration config;

    private final static Logger logger = LoggerFactory.getLogger(CassandraDriver.class);

    /**
     * Validates a Builder configuration and returns the configured driver. Tied to .build() procedure
     * @param config driver configuration
     */
    private static CassandraDriver buildFrom(Builder.Configuration config) throws DriverException{
    	if(config == null)
    		throw new DriverException(401,"Configuration is required");
    	
    	return new CassandraDriver(config);    	
    }
    
    /**
     * Cassandra Driver Builder. Please refer to builder docs for details
     * @return builder Instance of CassandraDriver.Builder
     */
    
    public static CassandraDriver.Builder builder() {
    	logger.debug("Using builder");
    	return new CassandraDriver.Builder();
    }
    
    /**
     * Initializes the Driver with configuration object
     * @param config driver configuration
     */
    protected CassandraDriver(Builder.Configuration config) {
    	config.validate();
    	this.config = config;
        this.clusterMap = new HashMap<String, Cluster>();
        this.sessionMap = new HashMap<String, Session>();
        this.mappingManagerMap = new HashMap<String, MappingManager>();
        this.driverConfig = new DatabaseCache<DriverConfig>(DriverConfig.class, this);    	
    }
    
    /**
     * Initializes the Driver
     * @param username Name of User
     * @param password Password of user
     * @param contactPoints Comma separated list of contact points for Cassandra cluster. Order is ignored.
     * @param port Port Cassandra is listening on. Typically 9042
     * @param localDC Which dc to consider local
     * @param keyspace Default keyspace
     */
    public CassandraDriver(String username, String password, String contactPoints, int port,String localDC, String keyspace) {
    	this(
			new Builder()
				.withUsername(username)
				.withPassword(password)
				.withContactPoints(contactPoints)
				.withPort(port)
				.withLocalDC(localDC)
				.withKeyspace(keyspace)
				.getConfiguration()
			);
    }

	/**
     * Get a cluster connection or create if missing. If key is default then the default cluster is used. If key is not then it makes a local only cluster connection to a DC of the given key
     * @param key Key for the cluster connection
     * @return Cluster object for key
     */
    private Cluster getCluster(String key) {
        Cluster cluster;
        if(! clusterMap.containsKey(key)) {
            switch(key) {
                case "default":
                	switch(this.config.defaults.clusterType) {
                		case "HA":
                			cluster = createHACluster(config.localDC);
                			break;
                		case "Single":
                		default:
            				cluster = createSingleDCCluster(config.localDC);
            				break;
                	}
                    break;
                default:
                    cluster = createSingleDCCluster(key);
            }
            logger.info("Created new cluster connection for key "+key);
            clusterMap.put(key,cluster);
        }
        return clusterMap.get(key);
    }

    /**
     * Get a session for the key
     * @param key Key for the session
     * @return Session object for key
     * @throws DriverException - Driver exception mapped to error code
     */
    protected Session getSession(String key) throws DriverException {
        if (!sessionMap.containsKey(key)) {
            try {
                sessionMap.put(key, getCluster(key).connect(config.keyspace));
                logger.info("Opened new session in "+key+" to "+config.keyspace);
            }
            catch (Exception e) {
                DriverException driverException = new DriverException(e);
                throw driverException;
            }
        }
        return sessionMap.get(key);
    }

    /**
     * Get a mapping manager for the key
     * @param key Key for the Mapping Manager
     * @return Mapping Manager object for key
     */
    private MappingManager getMappingManager(String key) {
        if(!mappingManagerMap.containsKey(key)) {
            mappingManagerMap.put(key, new MappingManager(getSession(key)));
        }
        return mappingManagerMap.get(key);
    }

    /**
     * Initializes the Cluster that prefers local dc but allows remote dc
     * @param localDC Which dc to consider local
     * @return Cluster object for supplied details
     */
    private Cluster createHACluster(String localDC) {
        logger.info("Creating new HA cluster with local set to "+localDC);
        //Define a DC and Token aware policy
        TokenAwarePolicy loadBalancingPolicy = new TokenAwarePolicy(
                DCAwareRoundRobinPolicy.builder()
                        .withLocalDc(config.localDC)
                        .withUsedHostsPerRemoteDc(config.useRemoteConnections)
                        .allowRemoteDCsForLocalConsistencyLevel()
                        .build()
        );

        return createCluster(loadBalancingPolicy);
    }

    /**
     * Initializes the Cluster for a single datacenter that does not allow a remote dc
     * @param datacenter Which dc to use
     * @return Cluster object for supplied details
     */
    private Cluster createSingleDCCluster(String datacenter) {
        logger.info("Creating new Single DC cluster with datacenter set to "+datacenter);
        TokenAwarePolicy loadBalancingPolicy = new TokenAwarePolicy(
                DCAwareRoundRobinPolicy.builder()
                        .withLocalDc(datacenter)
                        .build()
        );

        return createCluster(loadBalancingPolicy);
    }

    /**
     * Initializes the Cluster
     * @param loadBalancingPolicy Configured Load Balancing Policy
     * @return Cluster object for supplied details
     */
    private Cluster createCluster(LoadBalancingPolicy loadBalancingPolicy) throws DriverException {
        //Set the local DC to use min 1 connection (34k threads) up to 3 max
        PoolingOptions poolingOptions = new PoolingOptions()
                //.setNewConnectionThreshold(HostDistance.LOCAL, 200) //default
                //.setMaxRequestsPerConnection(HostDistance.LOCAL, 256) //default
                //.setIdleTimeoutSeconds(120) //default
                //.setNewConnectionThreshold(HostDistance.LOCAL, 800) //default
                //.setMaxRequestsPerConnection(HostDistance.LOCAL, 1024) //default
                .setConnectionsPerHost(HostDistance.LOCAL, config.connections.local.min, config.connections.local.max)
                .setConnectionsPerHost(HostDistance.REMOTE, config.connections.remote.min,config.connections.remote.max);


        SpeculativeExecutionPolicy speculativeExecutionPolicy = new ConstantSpeculativeExecutionPolicy(
                config.speculativeExecution.delay,
                config.speculativeExecution.executions
        );

        SocketOptions socketOptions = new SocketOptions()
                .setConnectTimeoutMillis(config.timeout.connection)
                .setReadTimeoutMillis(config.timeout.read);

        ExponentialReconnectionPolicy reconnectionPolicy = new ExponentialReconnectionPolicy(config.reconnection.delay, config.reconnection.maxDelay);

        RetryPolicy retryPolicy = FallthroughRetryPolicy.INSTANCE;
        
        Cluster.Builder clusterBuilder = Cluster.builder()
                .addContactPoints(config.contactPoints.split(","))
                .withLoadBalancingPolicy(loadBalancingPolicy)
                .withPort(config.port)
                .withSpeculativeExecutionPolicy(speculativeExecutionPolicy)
                .withCredentials(config.username, config.password)
                .withPoolingOptions(poolingOptions)
                .withSocketOptions(socketOptions)
                .withReconnectionPolicy(reconnectionPolicy)
                .withRetryPolicy(retryPolicy);
        
        if (config.ssl.node) {        	
        	try {                
        		if (!config.ssl.truststore.path.isEmpty()) {
					KeyStore keyStore = KeyStore.getInstance("JKS");
					InputStream trustStore = new FileInputStream(config.ssl.truststore.path);
					keyStore.load(trustStore, config.ssl.truststore.password.toCharArray());
					TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
					trustManagerFactory.init(keyStore);
					trustStore.close();		
	
	                SSLContext sslContext = SSLContext.getInstance("TLS");
	                sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
	                
	                
	                @SuppressWarnings("deprecation")
		        	JdkSSLOptions sslOptions = RemoteEndpointAwareJdkSSLOptions.builder()
		        			  .withSSLContext(sslContext)
		        			  .build();
		        	
		        	clusterBuilder = clusterBuilder.withSSL(sslOptions);
        		} 
        		else {
        			clusterBuilder  = clusterBuilder.withSSL();
        		}
        	}
            catch (Exception e) {
                DriverException driverException = new DriverException(e);
                throw driverException;
            }
        }
        
        Cluster cluster = clusterBuilder.build();
        return cluster;
    }

    /**
     * Convenience function for raw CQL execution. Ignores results
     * This should on be used on edge cases and is not type safe. Most queries should go through mapped objects.
     * @param cql query
     * @throws DriverException - Driver exception mapped to error code
     */
    public void execute(String cql) throws DriverException{        
        try {
            logger.debug("Executing "+cql+" on default");
            this.getSession(getConnectionKey("default")).execute(cql);
        }
        catch (Exception e) {
            DriverException driverException = new DriverException(e);
            throw driverException;
        }
    }

    /**
     * Procedure executes query and return first row. It does not support consistency level settings of driver_config.
     * This should on be used on edge cases and is not type safe. Most queries should go through mapped objects.
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param cql query
     * @return ResultSet containing one row for query
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> T executeOne(Class<T> c, String cql) throws DriverException {
        logger.debug("Executing "+cql+" on "+getConnectionKey(c));
        try {
        	return this.getMapper(c).map(this.getSession(getConnectionKey(c)).execute(cql)).one();
	    }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Procedure executes query and returns all rows. It does not support consistency level settings of driver_config.
     * This should on be used on edge cases and is not type safe. Most queries should go through mapped objects.
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param cql cql query
     * @return List of objects
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> List<T> executeAll(Class<T> c, String cql) throws DriverException {
        logger.debug("Executing "+cql+" on "+getConnectionKey(c));
        try {
        	return this.getMapper(c).map(this.getSession(getConnectionKey(c)).execute(cql)).all();
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Get an object by passing an instance of the given object with the key populated. All other fields are ignored
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Object containing keys populated
     * @return Object
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> T getById(Class<T> c, T o) throws DriverException {
        logger.debug("Getting "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" values "+o.toString()+" from "+getConnectionKey(c));
        try {
            return this.getMapper(c).get(buildID(c,o,"read"));
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Get one object from a partition by passing an instance of the given object with the partition key and optionally clustering keys populated. All other fields are ignored
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Object containing keys populated
     * @return Instance of Objects
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> T getOneById(Class<T> c, T o) throws DriverException {
        logger.debug("Getting One "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" values "+o.toString()+" from "+getConnectionKey(c));
        try {
        	Select select = this.generateSelectQuery(c, o);
	       	logger.debug("Running Query: "+select.getQueryString());       	
        	return this.getMapper(c).map(this.getSession(getConnectionKey(c)).execute(select)).one();
        	
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Get all objects from a partition by passing an instance of the given object with the partition key and optionally clustering keys populated. All other fields are ignored
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Object containing keys populated
     * @return List of Objects
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> List<T> getAllById(Class<T> c, T o) throws DriverException {
        logger.debug("Getting All "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" values "+o.toString()+" from "+getConnectionKey(c));
        try {
        	Select select = this.generateSelectQuery(c, o);
	       	logger.debug("Running Query: "+select.getQueryString());       	
        	return this.getMapper(c).map(this.getSession(getConnectionKey(c)).execute(select)).all();
        	
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Generate a select query using annotations and reflection
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Object containing keys populated
     * @return Select object
     * @throws DriverException - Driver exception mapped to error code
     */    
    protected <T extends AbstractCassandraTable> Select generateSelectQuery(Class<T> c, T o) throws DriverException {
        try {
        	Select select = QueryBuilder.select().from(c.getAnnotation(Table.class).name());        
	       	for(Field val : c.getDeclaredFields()) {
	       		 if(val.getAnnotationsByType(com.datastax.driver.mapping.annotations.PartitionKey.class).length > 0) {	       			
	       			if(o.getClass().getMethod("get"+StringUtils.capitalize(val.getName())).invoke(o) != null) {
	       				select.where().and(QueryBuilder.eq(val.getAnnotation(com.datastax.driver.mapping.annotations.Column.class).name(), o.getClass().getMethod("get"+StringUtils.capitalize(val.getName())).invoke(o)));
	       			}
	       		 }
	       		 if(val.getAnnotationsByType(com.datastax.driver.mapping.annotations.ClusteringColumn.class).length > 0) {	       			
	       			if(o.getClass().getMethod("get"+StringUtils.capitalize(val.getName())).invoke(o) != null) {
	       				select.where().and(QueryBuilder.eq(val.getAnnotation(com.datastax.driver.mapping.annotations.Column.class).name(), o.getClass().getMethod("get"+StringUtils.capitalize(val.getName())).invoke(o)));
	       			}
	       		 }
	         }
        	return select;
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Get an object by supplying a solr query
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param solrQueryString string representing the solr query (See https://docs.datastax.com/en/dse/5.1/dse-dev/datastax_enterprise/search/siQuerySyntax.html#siQuerySyntax)
     * @param limit limit the number of results
     * @return List of Objects
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> List<T> getAllBySolr(Class<T> c, String solrQueryString, int limit) throws DriverException {
        if(!config.features.solr) {
        	throw new DriverException(401,"Solr is disabled");
        }    	
    	logger.debug("Getting All from "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" with solar_query "+solrQueryString+" from "+getConnectionKey("solar"));
        try {
        	Select select = QueryBuilder.select().from(c.getAnnotation(Table.class).name()); 
        	select.where().and(QueryBuilder.eq("solr_query", solrQueryString)).limit(limit);
	       	logger.debug("Running Query: "+select.getQueryString());
        	return this.getMapper(c).map(this.getSession(config.defaults.solrDC).execute(select)).all();
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }


    /**
     * Get an object by supplying a solr query. Defaults to limit of 10
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param solrQueryString string representing the solr query (See https://docs.datastax.com/en/dse/5.1/dse-dev/datastax_enterprise/search/siQuerySyntax.html#siQuerySyntax)
     * @return List of Objects
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> List<T> getAllBySolr(Class<T> c, String solrQueryString) throws DriverException {
    	return getAllBySolr(c,solrQueryString,10);
    }

    /**
     * Get count for solr query
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param solrQueryString string representing the solr query (See https://docs.datastax.com/en/dse/5.1/dse-dev/datastax_enterprise/search/siQuerySyntax.html#siQuerySyntax)
     * @return count of results
     * @throws DriverException - Driver exception mapped to error code
     */    
    public <T extends AbstractCassandraTable> Long getCountBySolr(Class<T> c, String solrQueryString) throws DriverException {
    	if(!config.features.solr) {
        	throw new DriverException(401,"Solr is disabled");
        }         
        logger.debug("Getting All from "+c.getAnnotation(Table.class).keyspace()+".”+c.getAnnotation(Table.class).name()+” with solar_query "+solrQueryString+" from "+getConnectionKey("solar"));
        try {
            Select select = QueryBuilder.select().countAll().from(c.getAnnotation(Table.class).name());
            select.where().and(QueryBuilder.eq("solr_query", solrQueryString));
                logger.debug("Running Query: "+select.getQueryString());
                ResultSet result = this.getSession(config.defaults.solrDC).execute(select);
                if(result != null) {
                    List<Row> rowList = result.all();
                    if(rowList != null && rowList.size()>0) {
                        return new Long(rowList.get(0).getLong(0));
                    }
                    else {
                        return new Long(0);
                    }
                }
                else {
                    return new Long(0);
                }
        }
         catch (Exception e) {
             DriverException driverException = new DriverException(e);
             throw driverException;
         }
    }
    
    /**
     * Build the ID string to pass to mapper function including any options
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Populated object
     * @param type read/write
     * @return Object array to pass to mapper function
     */
    private <T extends AbstractCassandraTable> Object[] buildID(Class<T> c, T o,String type)  {
        Object[] id = new Object[o.getID().length+1];
        System.arraycopy(o.getID(),0,id,0,o.getID().length);
        id[o.getID().length] = getConsistencyLevel(c,type);
        return id;
    }

    /**
     * Check if an  object exists by passing an instance of the given object with the key populated. All other fields are ignored
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Object containing keys populated
     * @return True if exists, false if not
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> boolean existsById(Class<T> c, T o) throws DriverException {
        logger.debug("Checking for existing "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" values "+o.toString()+" in "+getConnectionKey(c));        
        try {
        	T obj = this.getMapper(c).get(buildID(c,o,"read"));
            if (obj != null){
                return true;
            }
            else {
                return false;
            }
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Delete an object by passing an instance of the given object with the key populated. All other fields are ignored
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Object containing keys populated
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> void delete(Class<T> c, T o) throws DriverException {
        logger.debug("Deleting "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" with values "+o.toString()+" from "+getConnectionKey(c));
        try {
        	this.getMapper(c).delete(o,getConsistencyLevel(c,"write"));
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Delete an object asynchronously by passing an instance of the given object with the key populated. All other fields are ignored
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Object containing keys populated
     * @return ListenableFuture for query
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> ListenableFuture<Void> deleteAsync(Class<T> c, T o) throws DriverException {
        logger.debug("Deleting asynchronously "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" with values "+o.toString()+" from "+getConnectionKey(c));
        try {
        	return this.getMapper(c).deleteAsync(o,getConsistencyLevel(c,"write"));
        }
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Save an object by passing an instance of the given object. Inserts or updates as necessary. NOTE: Nulls are not persisted to the database.
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Populated object
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> void save(Class<T> c, T o) throws DriverException{
        logger.debug("Saving to  "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" values "+o.toString()+" to "+getConnectionKey(c));
    	try {
    		this.getMapper(c).save(o,getConsistencyLevel(c,"write"));
    	}
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Save an object asynchronously by passing an instance of the given object. Inserts or updates as necessary. All other fields are ignored
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param o Populated object
     * @return ListenableFuture for query
     * @throws DriverException - Driver exception mapped to error code
     */
    public <T extends AbstractCassandraTable> ListenableFuture<Void> saveAsync(Class<T> c, T o) throws DriverException{
        logger.debug("Saving (asynchronously) to  "+c.getAnnotation(Table.class).keyspace()+"."+c.getAnnotation(Table.class).name()+" values "+o.toString()+" to "+getConnectionKey(c));
    	try {
    		return this.getMapper(c).saveAsync(o,getConsistencyLevel(c,"write"));
    	}
	    catch (Exception e) {
	        DriverException driverException = new DriverException(e);
	        throw driverException;
	    }
    }

    /**
     * Get the connection key from the driver_config table. If not specified then uses "default"
     * @param <T> Domain Object for results
     * @param c Class of object
     * @return Connection Key.
     */
    protected <T extends AbstractCassandraTable> String getConnectionKey(Class<T> c) {
        String key = "default";
        if(c.isAnnotationPresent(Table.class)) {
            Table annotation = c.getAnnotation(Table.class);
            String tableName = annotation.name();
            key = getConnectionKey(tableName);
        }
        return key;
    }    

    /**
     * Get mapper for the given class
     * @param <T> Domain Object for results
     * @param c Class of object
     * @return Mapper object for class
     */
    private <T extends AbstractCassandraTable> Mapper<T> getMapper(Class<T> c) {
    	Mapper<T> mapper = this.getMappingManager(getConnectionKey(c)).mapper(c);
    	if(config.defaults.saveNulls) {
    		mapper.setDefaultSaveOptions(Mapper.Option.saveNullFields(true));
    	}
    	else {
    		mapper.setDefaultSaveOptions(Mapper.Option.saveNullFields(false));    		
    	}
        return mapper;
    }

    /**
     * Get the connection key from the driver_config table. If not specified then uses "default"
     * @param tableName table name
     * @return Connection Key.
     */
    private String getConnectionKey(String tableName) {
        String key = "default";
        if (config.features.driverConfig && !tableName.equals("driver_config")) {
            DriverConfig tmpDriverConfig = null;
            driverConfig.get(tableName);
            if (tmpDriverConfig == null) {
            	tmpDriverConfig = driverConfig.get("default");
            }
            if (tmpDriverConfig != null) {
                key = tmpDriverConfig.getDataCenter();
                logger.info("Found Connection Key for " + tableName);
            }
        }
        logger.debug("Connection Key set to "+key+" for "+tableName);
        return key;
    }

    /**
     * Get the consistency level from driver_config table for the query.
     * @param <T> Domain Object for results
     * @param c Class of object
     * @param type Read or Write
     * @return Mapper Option for consistency level
     */
    private <T extends AbstractCassandraTable> Mapper.Option getConsistencyLevel(Class<T> c,String type) {
        Mapper.Option option = Mapper.Option.consistencyLevel(ConsistencyLevel.valueOf(config.defaults.consistencyLevel));
        if(c.isAnnotationPresent(Table.class)) {
            Table annotation = c.getAnnotation(Table.class);
            String tableName = annotation.name();
            if(config.features.driverConfig && !tableName.equals("driver_config")) {
                DriverConfig tmpDriverConfig = null;
                tmpDriverConfig = driverConfig.get(tableName);
                if (config == null) {
                	tmpDriverConfig = driverConfig.get("default");
                }
                if (tmpDriverConfig != null) {
                    switch (type) {
                        case "read":
                            option = Mapper.Option.consistencyLevel(ConsistencyLevel.valueOf(tmpDriverConfig.getReadConsistency()));
                            break;
                        case "write":
                            option = Mapper.Option.consistencyLevel(ConsistencyLevel.valueOf(tmpDriverConfig.getWriteConsistency()));
                            break;
                    }
                }
            }
        }
        return option;
    }

    /**
     * Close cluster connections
     */
    @PreDestroy
    public void close() {
        for(String key : this.clusterMap.keySet()) {
            this.clusterMap.get(key).close();
            logger.info("Closed cluster connection for key "+key);
        }

    }
}
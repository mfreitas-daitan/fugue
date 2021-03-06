/*
 *
 *
 * Copyright 2018 Symphony Communication Services, LLC.
 *
 * Licensed to The Symphony Software Foundation (SSF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.symphonyoss.s2.fugue.deploy;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;

import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.symphonyoss.s2.common.concurrent.NamedThreadFactory;
import org.symphonyoss.s2.common.dom.IStringProvider;
import org.symphonyoss.s2.common.dom.TypeAdaptor;
import org.symphonyoss.s2.common.dom.json.IJsonArray;
import org.symphonyoss.s2.common.dom.json.IJsonDomNode;
import org.symphonyoss.s2.common.dom.json.IJsonObject;
import org.symphonyoss.s2.common.dom.json.ImmutableJsonDom;
import org.symphonyoss.s2.common.dom.json.ImmutableJsonObject;
import org.symphonyoss.s2.common.dom.json.JsonObject;
import org.symphonyoss.s2.common.dom.json.MutableJsonDom;
import org.symphonyoss.s2.common.dom.json.MutableJsonObject;
import org.symphonyoss.s2.common.exception.InvalidValueException;
import org.symphonyoss.s2.fugue.cmd.CommandLineHandler;
import org.symphonyoss.s2.fugue.naming.Name;

/**
 * Abstract base class for deployment utility implementations, to be subclassed for each cloud service provider.
 * 
 * @author Bruce Skingle
 *
 */
public abstract class FugueDeploy extends CommandLineHandler
{
  /** The label for a configuration */
  public static final String   CONFIG           = "config";
  /** The label for an environment */
  public static final String   ENVIRONMENT      = "environment";
  /** The label for an environment type */
  public static final String   ENVIRONMENT_TYPE = "environmentType";
  /** The label for a realm */
  public static final String   REALM            = "realm";
  /** The label for a region */
  public static final String   REGION           = "region";
  /** The label for a track */
  public static final String   TRACK            = "track";
  /** The label for a station */
  public static final String   STATION          = "station";
  /** The label for a service */
  public static final String   SERVICE          = "service";
  /** The label for a policy / role */
  public static final String   POLICY           = "policy";
  /** The label for a tenant */
  public static final String   TENANT           = "tenant";
  /** The label for an action */
  public static final String   ACTION           = "action";
  /** The file name extension for a JSON document */
  public static final String   DOT_JSON         = ".json";
  /** The label for an ID */
  public static final String   ID               = "id";

  /** The suffix for a label to indicate that it is the ID of the object rather than the object itself */
  public static final String   ID_SUFFIX        = "Id";
  
  /** The prefix for the names of fugue entities in the CSP */
  public static final String FUGUE_PREFIX = "fugue-";

  private static final String              CONFIG_DIR                    = CONFIG + "/";
  private static final String              SERVICE_DIR                   = CONFIG_DIR + SERVICE;
  private static final String              DEFAULTS                      = "defaults";
  private static final String              FQ_SERVICE_NAME               = "fullyQualifiedServiceName";
  private static final String              FQ_INSTANCE_NAME              = "fullyQualifiedInstanceName";

  private static final Logger              log_                          = LoggerFactory.getLogger(FugueDeploy.class);
  private static final String              SINGLE_TENANT                 = "singleTenant";
  private static final String              MULTI_TENANT                  = "multiTenant";
  private static final String              PORT                          = "port";
  private static final String              PATHS                         = "paths";
  private static final String              HEALTH_CHECK_PATH             = "healthCheckPath";
  private static final String              CONTAINERS                    = "containers";

  private static final String              DNS_SUFFIX                    = "dnsSuffix";

  private final String                     cloudServiceProvider_;
  private final ConfigProvider             provider_;
  private final ConfigHelper[]             helpers_;

  private String                           track_;
  private String                           station_;
  private String                           service_;
  private String                           environment_;
  private String                           environmentType_;
  private String                           realm_;
  private String                           region_;
  private String                           tenant_;

  private boolean                          primaryEnvironment_           = false;
  private boolean                          primaryRegion_                = false;

  private FugueDeployAction                action_;
  private String                           dnsSuffix_;

  private ExecutorService                  executor_                    = Executors.newFixedThreadPool(20, new NamedThreadFactory("Batch", true));
  
  private List<DeploymentContext>          tenantContextList_           = new LinkedList<>();
  private DeploymentContext                multiTenantContext_;
  
  private Map<String, String>              tags_                        = new HashMap<>();
  
  protected abstract DeploymentContext  createContext(String tenantId);
  
  protected abstract void validateAccount(IJsonObject<?> config);
  
  
  /**
   * Constructor.
   * 
   * @param cloudServiceProvider  Name of the CSP "amazon" or "google".
   * @param provider              A config provider.
   * @param helpers               Zero or more config helpers.
   */
  public FugueDeploy(String cloudServiceProvider, ConfigProvider provider, ConfigHelper ...helpers)
  {
    cloudServiceProvider_ = cloudServiceProvider;
    provider_ = provider;
    helpers_ = helpers == null ? new ConfigHelper[0] : helpers;
    
    withFlag(null,  TRACK,                "FUGUE_TRACK",                String.class,   false, false,   (v) -> track_               = v);
    withFlag(null,  STATION,              "FUGUE_STATION",              String.class,   false, false,   (v) -> station_             = v);
    withFlag('s',   SERVICE,              "FUGUE_SERVICE",              String.class,   false, false,   (v) -> service_             = v);
    withFlag('v',   ENVIRONMENT_TYPE,     "FUGUE_ENVIRONMENT_TYPE",     String.class,   false, false,   (v) -> environmentType_     = v);
    withFlag('e',   ENVIRONMENT,          "FUGUE_ENVIRONMENT",          String.class,   false, false,   (v) -> environment_         = v);
    withFlag('r',   REALM,                "FUGUE_REALM",                String.class,   false, false,   (v) -> realm_               = v);
    withFlag('g',   REGION,               "FUGUE_REGION",               String.class,   false, false,   (v) -> region_              = v);
    withFlag('t',   TENANT,               "FUGUE_TENANT",               String.class,   false, false,   (v) -> tenant_              = v);
    withFlag('a',   ACTION,               "FUGUE_ACTION",               String.class,   false, true,    (v) -> setAction(v));
    withFlag('E',   "primaryEnvironment", "FUGUE_PRIMARY_ENVIRONMENT",  Boolean.class,  false, false,   (v) -> primaryEnvironment_  = v);
    withFlag('G',   "primaryRegion",      "FUGUE_PRIMARY_REGION",       Boolean.class,  false, false,   (v) -> primaryRegion_       = v);
    
    provider_.init(this);
    
    for(ConfigHelper helper : helpers_)
      helper.init(this);
    
    multiTenantContext_ = createContext(null);
  }

  private void setAction(String v)
  {
    action_ = FugueDeployAction.valueOf(v);
      
    if(action_ == null)
      throw new IllegalArgumentException("\"" + v + "\" is not a valid action");
  }

  /**
   * Verify that the given value is non-null
   * 
   * @param name  Name of the value for exception message
   * @param value A Value
   * 
   * @return      The given value, which is guaranteed to be non-null
   * 
   * @throws      IllegalArgumentException if value is null.
   */
  public @Nonnull <T> T require(String name, T value)
  {
    if(value == null)
      throw new IllegalArgumentException("\"" + name + "\" is a required parameter");
    
    return value;
  }

  /**
   * 
   * @return The service ID
   */
  public @Nonnull String getService()
  {
    return require(SERVICE, service_);
  }
  
  /**
   * 
   * @return The station ID.
   */
  public @Nonnull String getStation()
  {
    return require(STATION, station_);
  }
  
  /**
   * 
   * @return The track ID.
   */
  public @Nonnull String getTrack()
  {
    return require(TRACK, track_);
  }
  
  /**
   * 
   * @return The environment ID.
   */
  public @Nonnull String getEnvironment()
  {
    return require(ENVIRONMENT, environment_);
  }

  /**
   * 
   * @return The realm ID
   */
  public @Nonnull String getRealm()
  {
    return require(REALM, realm_);
  }
  
  /**
   * 
   * @return The region ID
   */
  public @Nonnull String getRegion()
  {
    return require(REGION, region_);
  }
  
  /**
   * 
   * @return The environment type "dev", "qa" etc.
   */
  public @Nonnull String getEnvironmentType()
  {
    return require("environmentType", environmentType_);
  }
  
  /**
   * 
   * @return true if this is the primary environment for this tenant/service
   */
  public boolean isPrimaryEnvironment()
  {
    return primaryEnvironment_;
  }
  
  /**
   * 
   * @return true if this is the primary region for this tenant/service
   */
  public boolean isPrimaryRegion()
  {
    return primaryRegion_;
  }

  /**
   * @return the environment type dns suffix.
   */
  public String getDnsSuffix()
  {
    return dnsSuffix_;
  }
  
  protected void populateTags(Map<String, String> tags)
  {
    tagIfNotNull("FUGUE_ENVIRONMENT_TYPE",  environmentType_);
    tagIfNotNull("FUGUE_ENVIRONMENT",       environment_);
    tagIfNotNull("FUGUE_REALM",             realm_);
    tagIfNotNull("FUGUE_REGION",            region_);
    tagIfNotNull("FUGUE_SERVICE",           service_);
  }
  
  protected void tagIfNotNull(String name, String value)
  {
    if(value != null)
      tags_.put(name, value);
  }

  protected Map<String, String> getTags()
  {
    return tags_;
  }

  /**
   * Perform the deployment.
   */
  public void deploy()
  {
    
    
    if(action_ == FugueDeployAction.DeployStation)
    {
      getStationConfig();
    }
    else
    {
      if(tenant_ != null)
        tenantContextList_.add(createContext(tenant_));
    }

    log_.info("ACTION           = " + action_);
    log_.info("ENVIRONMENT_TYPE = " + environmentType_);
    log_.info("ENVIRONMENT      = " + environment_);
    log_.info("REALM            = " + realm_);
    log_.info("REGION           = " + region_);
    
    for(int i=0 ; i<tenantContextList_.size() ; i++)
      log_.info(String.format("TENANT[%3d]      = %s", i, tenantContextList_.get(i).getTenantId()));
    
    populateTags(tags_);
    
    // All actions need multi-tenant config
    ImmutableJsonObject multiTenantIdConfig   = createIdConfig(null);
    ImmutableJsonObject environmentConfig     = fetchEnvironmentConfig();
    ImmutableJsonObject multiTenantDefaults   = fetchMultiTenantDefaults(multiTenantIdConfig);
    ImmutableJsonObject multiTenantOverrides  = fetchMultiTenantOverrides(multiTenantIdConfig);
    ImmutableJsonObject multiTenantConfig     = overlay(
        multiTenantDefaults,
        environmentConfig,
        multiTenantOverrides,
        multiTenantIdConfig
        );

    validateAccount(multiTenantConfig);
    
    multiTenantContext_.setConfig(multiTenantConfig);
    
    dnsSuffix_   = multiTenantConfig.getRequiredString(DNS_SUFFIX);
    
    final boolean deployConfig;
    final boolean deployContainers;
    
    switch (action_)
    {
      case CreateEnvironmentType:
        deployConfig = false;
        deployContainers = false;
        log_.info("Creating environment type \"" + environmentType_ + "\"");
        multiTenantContext_.createEnvironmentType();
        break;

      case CreateEnvironment:
        deployConfig = false;
        deployContainers = false;
        log_.info("Creating environment \"" + environment_ + "\"");
        multiTenantContext_.createEnvironment();
        break;

      case Deploy:
      case DeployStation:
        deployConfig = true;
        deployContainers = true;
//        MutableJsonObject serviceJson = deployConfig();
//        
//        if(serviceJson != null)
//          deployContainers(serviceJson);
        break;
        
      case DeployConfig:
//        deployConfig();
          deployConfig = true;
          deployContainers = false;
        break;
        
      default:
        throw new IllegalStateException("Unrecognized action " + action_);
    }
    
    

    if(deployConfig)
    {
      ImmutableJsonObject serviceJson;
      String dir = SERVICE_DIR + "/" + getService();
      
      try
      {
        serviceJson = provider_.fetchConfig(dir, SERVICE + ".json").immutify();
        
        log_.info("Service=" + serviceJson);
      }
      catch(IOException e)
      {
        throw new IllegalArgumentException("Unknown service \"" + service_ + "\".", e);
      }
      
      dir = dir + "/" + cloudServiceProvider_ + "/" + POLICY;
      
      
      
      // deploy multi-tenant containers first
      
      multiTenantContext_.setPolicies(fetchPolicies(dir, MULTI_TENANT));
      
      multiTenantContext_.processConfigAndPolicies();
      
      IJsonObject<?>                  containerJson           = (IJsonObject<?>)serviceJson.get(CONTAINERS);
      Map<String, JsonObject<?>>  singleTenantInitMap     = new HashMap<>();
      Map<String, JsonObject<?>>  multiTenantInitMap      = new HashMap<>();
      Map<String, JsonObject<?>>  singleTenantServiceMap  = new HashMap<>();
      Map<String, JsonObject<?>>  multiTenantServiceMap   = new HashMap<>();
      
      if(deployContainers)
      {
        // Load all the containers
        
        Iterator<String>                it                      = containerJson.getNameIterator();
        
        while(it.hasNext())
        {
          String name = it.next();
          IJsonDomNode c = containerJson.get(name);
          
          if(c instanceof JsonObject)
          {
            JsonObject<?> container = (JsonObject<?>)c;
            
            String tenancy = container.getRequiredString("tenancy");
            
            boolean singleTenant = "SINGLE".equals(tenancy);
            
            if("INIT".equals(container.getString("containerType", "SERVICE")))
            {
              if(singleTenant)
                singleTenantInitMap.put(name, container);
              else
                multiTenantInitMap.put(name, container);
            }
            else
            {
              if(singleTenant)
                singleTenantServiceMap.put(name, container);
              else
                multiTenantServiceMap.put(name, container);
            }
          }
        }
        
        // Deploy multi-tenant init containers
        
        multiTenantContext_.setContainers(multiTenantInitMap, multiTenantServiceMap);
        
        multiTenantContext_.deployInitContainers();
      }
      
      // Now we can do all the single tenant processes in parallel
      
      
      IBatch              batch                 = createBatch();
      Map<String, String> singleTenantPolicies  = fetchPolicies(dir, SINGLE_TENANT);
      
      for(DeploymentContext context : tenantContextList_)
      {
        context.setPolicies(singleTenantPolicies);
        
        String tenantId = context.getTenantId();
        
        batch.submit(() ->
        {
          
          ImmutableJsonObject tenantIdConfig         = createIdConfig(tenantId);
          ImmutableJsonObject tenantConfig           = fetchTenantConfig(tenantId);
          ImmutableJsonObject singleTenantIdConfig   = overlay(
              tenantConfig,
              multiTenantDefaults,
              environmentConfig,
              multiTenantOverrides,
              tenantIdConfig);
          ImmutableJsonObject singleTenantDefaults   = fetchSingleTenantDefaults(singleTenantIdConfig, tenantId);
          ImmutableJsonObject singleTenantOverrides  = fetchSingleTenantOverrides(singleTenantDefaults, tenantId);
          
          /*
           * At this point the defaults and environment config have all been merged in, we now just need to overlay the 
           * multiTenantOverrides and tenantIdConfig to ensure that they win over anything else which happened previously.
           * 
           * This is a bit confusing but it allows ConfigHelpers to use config from previous steps to identify what needs to
           * be added, specifically we need this for tenantId -> podName mapping.
           * 
           * Perhaps this can be removed once this mapping is in consul.
           */
          ImmutableJsonObject singleTenantConfig     = overlay(
              singleTenantOverrides,
              multiTenantOverrides,
              tenantIdConfig);
          
          context.setConfig(singleTenantConfig);
          context.processConfigAndPolicies();
          context.setContainers(singleTenantInitMap, singleTenantServiceMap);
          
          if(deployContainers)
          {
            context.deployInitContainers();
          }
        });
      }
      
      batch.waitForAllTasks();
      
      if(deployContainers)
      {
        // Now launch all the service containers in parallel
        
        IBatch containerBatch = createBatch();
            
        
        multiTenantContext_.deployServiceContainers(containerBatch);
        
        for(DeploymentContext context : tenantContextList_)
        {
          context.deployServiceContainers(containerBatch);
        }
        
        containerBatch.waitForAllTasks();
      }
    }
  }
  
  
  


  private IBatch createBatch()
  {
    return new ExecutorBatch(executor_);
//    return new SerialBatch();
  }

  private ImmutableJsonObject fetchTenantConfig(String tenantId)
  {
    try
    {
      return provider_.fetchConfig(CONFIG + "/" + TENANT, tenantId + DOT_JSON).immutify();
    }
    catch(IOException e)
    {
      throw new IllegalStateException("Unable to read tenant config", e);
    }
  }

  
  
  
  
  private ImmutableJsonObject createIdConfig(String tenantId)
  {
    MutableJsonObject idConfig = new MutableJsonObject();
    MutableJsonObject id = new MutableJsonObject();
    
    idConfig.add(ID, id);
    
    id.addIfNotNull(ENVIRONMENT + ID_SUFFIX,  environment_);
    id.addIfNotNull(ENVIRONMENT_TYPE,         environmentType_);
    id.addIfNotNull(REALM + ID_SUFFIX,        realm_);
    id.addIfNotNull(REGION + ID_SUFFIX,       region_);
    id.addIfNotNull(SERVICE + ID_SUFFIX,      service_);
    id.addIfNotNull(TENANT + ID_SUFFIX,       tenantId);
    
    return idConfig.immutify();
  }
  
  

  private ImmutableJsonObject overlay(JsonObject<?> ...objects)
  {
    MutableJsonObject config = new MutableJsonObject();
    
    for(JsonObject<?> object : objects)
      config.addAll(object, "#");
    
    return config.immutify();
  }

  private ImmutableJsonObject fetchMultiTenantDefaults(IJsonObject<?> idConfig)
  {
    MutableJsonObject config = idConfig.newMutableCopy();
    
    provider_.overlayDefaults(config);
    
    for(ConfigHelper helper : helpers_)
      helper.overlayDefaults(config);
    
    return config.immutify();
  }
  
  private ImmutableJsonObject fetchMultiTenantOverrides(IJsonObject<?> idConfig)
  {
    MutableJsonObject config = idConfig.newMutableCopy();
    
    provider_.overlayOverrides(config);
    
    for(ConfigHelper helper : helpers_)
      helper.overlayOverrides(config);
    
    return config.immutify();
  }
  
  private ImmutableJsonObject fetchEnvironmentConfig()
  {
    try
    {
      MutableJsonObject json  = new MutableJsonObject();
      
      String dir = CONFIG;
      
      fetch(false, json, dir, DEFAULTS, "defaults", "");
      
      dir = dir + "/" + ENVIRONMENT + "/" + environmentType_;
      
      fetch(true, json, dir, ENVIRONMENT_TYPE, "environment type", environmentType_);  
      
      if(environment_ == null)
        return json.immutify();
      
      dir = dir + "/" + environment_;
      
      fetch(true, json, dir, ENVIRONMENT, "environment", environment_);
      
      if(realm_ == null)
        return json.immutify();
      
      dir = dir + "/" + realm_;
      
      fetch(true, json, dir, REALM, "realm", realm_);
      
      if(region_ == null)
        return json.immutify();
      
      dir = dir + "/" + region_;
      
      fetch(true, json, dir, REGION, "region", region_);
      
      return json.immutify();
    }
    catch(IOException e)
    {
      throw new IllegalStateException("Unable to load environment config", e);
    }
  }

  private ImmutableJsonObject fetchSingleTenantDefaults(IJsonObject<?> idConfig, String tenantId)
  {
    MutableJsonObject config = idConfig.newMutableCopy();
    
    provider_.overlayDefaults(tenantId, config);
    
    for(ConfigHelper helper : helpers_)
      helper.overlayDefaults(tenantId, config);
    
    return config.immutify();
  }
  
  private ImmutableJsonObject fetchSingleTenantOverrides(IJsonObject<?> idConfig, String tenantId)
  {
    MutableJsonObject config = idConfig.newMutableCopy();
    
    provider_.overlayOverrides(tenantId, config);
    
    for(ConfigHelper helper : helpers_)
      helper.overlayOverrides(tenantId, config);
    
    return config.immutify();
  }

  private void getStationConfig()
  {
    MutableJsonObject json  = new MutableJsonObject();
    
    try
    {
      String dir = CONFIG + "/" + TRACK;

      fetch(true, json, dir, getTrack(), "Release Track", getTrack());
    }
    catch(FileNotFoundException e)
    {
      throw new IllegalArgumentException("No such track config", e);
    }
    catch(IOException e)
    {
      throw new IllegalStateException("Unable to read track config", e);
    }

    IJsonDomNode stationsNode = json.get("stations");
    
    if(stationsNode == null)
    {
      throw new IllegalStateException("Unable to read stations from track config");
    }
    
    if(stationsNode instanceof IJsonArray)
    {
      for(IJsonDomNode stationNode : ((IJsonArray<?>)stationsNode))
      {
        if(stationNode instanceof IJsonObject)
        {
          IJsonObject<?> station = (IJsonObject<?>)stationNode;
          
          String name = station.getRequiredString("name");
          
          if(getStation().equals(name))
          {
            environmentType_  = station.getRequiredString(ENVIRONMENT_TYPE);
            environment_      = station.getRequiredString(ENVIRONMENT);
            realm_            = station.getRequiredString(REALM);
            region_           = station.getRequiredString(REGION);
            
            IJsonDomNode tenantsNode = station.get("tenants");
            
            if(tenantsNode != null)
            {
              if(tenantsNode instanceof IJsonArray)
              {
                for(IJsonDomNode tenantNode : ((IJsonArray<?>)tenantsNode))
                {
                  if(tenantNode instanceof IStringProvider)
                  {
                    tenantContextList_.add(createContext(((IStringProvider)tenantNode).asString()));
                  }
                  else
                  {
                    throw new IllegalStateException("Invalid station config - tenants contains a non-string value.");
                  }
                }
              }
              else
              {
                throw new IllegalStateException("Invalid station config - tenants is not an array.");
              }
            }
          }
        }
        else
        {
          throw new IllegalStateException("Invalid track config - station \"" + stationNode + "\" is not an object.");
        }
      }
    }
    else
    {
      throw new IllegalStateException("Invalid track config - stations is not an array.");
    }
  }
  
  private void fetch(boolean required, MutableJsonObject json, String dir, String fileName, String entityType, String entityName) throws IOException
  {
    try
    {
      json.addAll(provider_.fetchConfig(dir, fileName + DOT_JSON), "#");
    }
    catch(FileNotFoundException e)
    {
      if(required)
        throw new IllegalArgumentException("No such " + entityType + " \"" + entityName + "\"", e);
      
      log_.warn("No " + entityType + " config");
    }
  }
  
  protected String getConfigName(String tenant)
  {
    return new Name(getEnvironmentType(), getEnvironment(), getRealm(), getRegion(), tenant, getService()).toString();
  }
  
  protected Map<String, String> fetchPolicies(String parentDir, String subDir)
  {
    Map<String, String> policies = new HashMap<>();
    
    try
    {
      String              dir               = parentDir + "/" + subDir;
      List<String>        files             = provider_.fetchFiles(dir);
      
      for(String file : files)
      {
        if(file.endsWith(FugueDeploy.DOT_JSON))
        {
          String name     = file.substring(0, file.length() - FugueDeploy.DOT_JSON.length());
          String template = provider_.fetchConfig(dir, file).immutify().toString();
          
          policies.put(name, template);
        }
        else
          throw new IllegalStateException("Unrecognized file type found in config: " + dir + "/" + file);
      }
    }
    catch(IOException e)
    {
      throw new IllegalStateException("Unable to load " + subDir + " policies.", e);
    }
    
    return policies;
  }

  protected abstract class DeploymentContext
  {
    private final String               tenantId_;
    private Map<String, String>        policies_;
    private ImmutableJsonObject        config_;
    private ImmutableJsonDom           configDom_;
    private Map<String, String>        templateVariables_;
    private StringSubstitutor          sub_;
    private Map<String, JsonObject<?>> initContainerMap_;
    private Map<String, JsonObject<?>> serviceContainerMap_;

    protected DeploymentContext(String tenantId)
    {
      tenantId_ = tenantId;
    }

    protected abstract void createEnvironmentType();
    
    protected abstract void createEnvironment();
    
    protected abstract void processRole(String name, String roleSpec);
    
    protected abstract void saveConfig();
    
    protected abstract void deployInitContainer(String name, int port, Collection<String> paths, String healthCheckPath); //TODO: maybe wrong signature
    
    protected abstract void deployServiceContainer(String name, int port, Collection<String> paths, String healthCheckPath);
   
    protected abstract void deployService();
    
    protected String getTenantId()
    {
      return tenantId_;
    }

    protected Map<String, String> getPolicies()
    {
      return policies_;
    }

    protected ImmutableJsonDom getConfigDom()
    {
      return configDom_;
    }

    protected Map<String, String> getTemplateVariables()
    {
      return templateVariables_;
    }

    protected StringSubstitutor getSub()
    {
      return sub_;
    }

    protected ImmutableJsonObject getConfig()
    {
      return config_;
    }

    protected Map<String, JsonObject<?>> getInitContainerMap()
    {
      return initContainerMap_;
    }

    protected Map<String, JsonObject<?>> getServiceContainerMap()
    {
      return serviceContainerMap_;
    }

    protected void setConfig(ImmutableJsonObject config)
    {
      config_              = config;
      configDom_           = new MutableJsonDom().add(config_).immutify();
      templateVariables_   = createTemplateVariables(config);
      sub_                 = new StringSubstitutor(templateVariables_);
    }

    protected void setPolicies(Map<String, String> policies)
    {
      policies_ = policies;
    }
    
    protected void setContainers(Map<String, JsonObject<?>> initContainerMap, Map<String, JsonObject<?>> serviceContainerMap)
    {
      initContainerMap_     = initContainerMap;
      serviceContainerMap_  = serviceContainerMap;
    }

    protected Map<String, String> createTemplateVariables(ImmutableJsonObject config)
    {
      Map<String, String> templateVariables = new HashMap<>();
      
      provider_.populateTemplateVariables(config, templateVariables);
      
      for(ConfigHelper helper : helpers_)
        helper.populateTemplateVariables(config, templateVariables);
      
      populateTemplateVariables(config, templateVariables);
      
      return templateVariables;
    }
    
    protected void populateTemplateVariables(ImmutableJsonObject config, Map<String, String> templateVariables)
    {
      IJsonObject<?>      id = config.getRequiredObject(ID);
      Iterator<String>    it = id.getNameIterator();
      
      while(it.hasNext())
      {
        String name = it.next();
        IJsonDomNode value = id.get(name);
        
        if(value instanceof IStringProvider)
        {
          templateVariables.put(name, ((IStringProvider)value).asString());
        }      
      }
      
      templateVariables.put(FQ_SERVICE_NAME, new Name(environmentType_, environment_, realm_, region_, service_).toString());
      
      if(tenantId_ != null)
      {
        templateVariables.put(FQ_INSTANCE_NAME, new Name(environmentType_, environment_, realm_, region_, tenantId_, service_).toString());
      }
    }
    
    /**
     * Load a template and perform variable substitution.
     * 
     * The template is provided as a Java resource and the expanded template is returned as a String.
     * 
     * @param fileName The name of the resource containing the template.
     * 
     * @return The expanded template.
     */
    protected String loadTemplateFromResource(String fileName)
    {
      try(InputStream template = getClass().getClassLoader().getResourceAsStream(fileName))
      {
        if(template == null)
          throw new IllegalArgumentException("Template \"" + fileName + "\" not found");
        
        return loadTemplateFromStream(template, fileName);
      }
      catch (IOException e)
      {
        throw new IllegalArgumentException("Unable to read template \"" + fileName + "\"", e);
      }
    }

    /**
     * Load a template and perform variable substitution.
     * 
     * The template is provided as a Java resource and the expanded template is returned as a String.
     * 
     * @param template An InputStream containing the template.
     * @param fileName The "Name" of the template for error messages.
     * 
     * @return The expanded template.
     */
    protected String loadTemplateFromStream(InputStream template, String fileName)
    {
      StringBuilder s = new StringBuilder();
      
      try(BufferedReader in = new BufferedReader(new InputStreamReader(template)))
      {
        StringSubstitutor sub = new StringSubstitutor(templateVariables_);
        String            line;
        
        while((line=in.readLine()) != null)
        {
          s.append(sub.replace(line));
          s.append(System.lineSeparator());
        }
        
        return s.toString();
      }
      catch (IOException e)
      {
        throw new IllegalArgumentException("Unable to read template \"" + fileName + "\"", e);
      }
    }
    
    private void processConfigAndPolicies()
    {
      IBatch batch = createBatch();
      
      batch.submit(() ->
      { 
        saveConfig();
      });
      
      for(Entry<String, String> entry : policies_.entrySet())
      {
        String name     = entry.getKey();
        String template = entry.getValue();
        
        batch.submit(() ->
        {
          String roleSpec = sub_.replace(template);
          
          processRole(name, roleSpec);
        });
      }
      
      batch.waitForAllTasks();
    }
    
    protected void deployInitContainers()
    {
      if(!initContainerMap_.isEmpty())
      {
        // Deploy service level assets, load balancers, DNS zones etc
        
        deployService();
        
        for(String name : initContainerMap_.keySet())
        {
          JsonObject<?> container = initContainerMap_.get(name);
          
          try
          {
            IJsonDomNode        portNode = container.get(PORT);
            int                 port = portNode == null ? 80 : TypeAdaptor.adapt(Integer.class, portNode);
            Collection<String>  paths = container.getListOf(String.class, PATHS);
            String              healthCheckPath = container.getString(HEALTH_CHECK_PATH, "/HealthCheck");
            
            deployInitContainer(name, port, paths, healthCheckPath);
          }
          catch(InvalidValueException e)
          {
            throw new IllegalStateException(e);
          }
        }
      }
    }
    
    protected void deployServiceContainers(IBatch batch)
    {
      for(String name : serviceContainerMap_.keySet())
      {
        JsonObject<?> container = serviceContainerMap_.get(name);
        
        batch.submit(() ->
        {
          try
          {
            IJsonDomNode        portNode = container.get(PORT);
            int                 port = portNode == null ? 80 : TypeAdaptor.adapt(Integer.class, portNode);
            Collection<String>  paths = container.getListOf(String.class, PATHS);
            String              healthCheckPath = container.getString(HEALTH_CHECK_PATH, "/HealthCheck");
            
            deployServiceContainer(name, port, paths, healthCheckPath);
          }
          catch(InvalidValueException e)
          {
            throw new IllegalStateException(e);
          }
        });
      }
    }
  }
}

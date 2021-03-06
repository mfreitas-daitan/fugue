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

package org.symphonyoss.s2.fugue.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.symphonyoss.s2.common.fault.ProgramFault;
import org.symphonyoss.s2.fugue.Fugue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * An implementation of IConfiguration which reads a JSON document from a file.
 * 
 * @author Bruce Skingle
 *
 */
public class FileConfiguration extends Configuration
{
  public static final IConfigurationFactory FACTORY = new IConfigurationFactory()
  {
    @Override
    public IConfiguration newInstance(String variableName)
    {
      return new FileConfiguration(variableName);
    }

  };
      
  private static final Logger log_ = LoggerFactory.getLogger(FileConfiguration.class);
  
  private FileConfiguration(String variableName)
  {
    String fugueConfig = Fugue.getRequiredProperty(variableName);

    File file = new File(fugueConfig);
    
    if(file.exists())
    {
      try(InputStream in = new FileInputStream(file))
      {
        log_.info("Loading config from file " + file.getAbsolutePath());
        
        loadConfig(in);
        setName(file.getAbsolutePath() + " ");
      }
      catch (FileNotFoundException e)
      {
        throw new ProgramFault(variableName + " is " + fugueConfig + " but this file does not exist.", e);
      }
      catch (IOException e)
      {
        log_.warn("Failed to close config input", e);
      }
    }
    else
    {
      throw new ProgramFault(variableName + " is " + fugueConfig + " but this file does not exist.");
    }
  }


  private void loadConfig(InputStream in)
  {
    ObjectMapper mapper = new ObjectMapper();
    
    try
    {
      JsonNode configSpec = mapper.readTree(in);
      
      setTree(configSpec);
    }
    catch (IOException e1)
    {
      throw new ProgramFault("Cannot parse config.", e1);
    }
  }
}

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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.symphonyoss.s2.common.dom.json.MutableJsonObject;
import org.symphonyoss.s2.common.dom.json.jackson.JacksonAdaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * GitHub implementation of ConfigProvider.
 * 
 * @author Bruce Skingle
 *
 */
public class GitHubConfigProvider extends ConfigProvider
{
  private static final Logger log_ = LoggerFactory.getLogger(GitHubConfigProvider.class);
  
  private static final String TYPE = "type";
  private static final String TYPE_FILE = "file";
  private static final String TYPE_DIR = "dir";
  
  private String organization_ = "SymphonyOSF";
  private String repo_;
  private String branch_ = "master";
  private String accessToken_;
  
  @Override
  public void init(FugueDeploy deployConfig)
  {
    super.init(deployConfig);
    
    deployConfig
      .withFlag('O', "gitHubOrganization", "GITHUB_ORG",    String.class, false, false, (v) -> organization_ = v)
      .withFlag('R', "gitHubRepo",         "GITHUB_REPO",   String.class, false, true,  (v) -> repo_ = v)
      .withFlag('B', "gitHubBranch",       "GITHUB_BRANCH", String.class, false, false, (v) -> branch_ = v)
      .withFlag('T', "gitHubToken",        "GITHUB_TOKEN",  String.class, false, true,  (v) -> accessToken_ = v);
  }
  
  private URL getUrl(String folderName, String fileName)
  {
    return getUrl(folderName, fileName, accessToken_);
  }
  
  private URL getLogUrl(String folderName, String fileName)
  {
    return getUrl(folderName, fileName, "XXXXXXXXX");
  }

  
  private URL getUrl(String folderName, String fileName, String accessToken)
  {
    try
    {
      return new URL(String.format("https://api.github.com/repos/%s/%s/contents/%s/%s?access_token=%s&ref=%s", 
          organization_, repo_, folderName, fileName,
          accessToken,
          branch_));
    }
    catch (MalformedURLException e)
    {
      throw new IllegalArgumentException(e);
    }
  }
  
  private URL getDirUrl(String fileName)
  {
    return getDirUrl(fileName, accessToken_);
  }
  
  private URL getLogDirUrl(String fileName)
  {
    return getDirUrl(fileName, "XXXXXXXXX");
  }
  
  private URL getDirUrl(String fileName, String accessToken)
  {
    try
    {
      return new URL(String.format("https://api.github.com/repos/%s/%s/contents/%s?access_token=%s&ref=%s", 
          organization_, repo_, fileName,
          accessToken,
          branch_));
    }
    catch (MalformedURLException e)
    {
      throw new IllegalArgumentException(e);
    }
  }
  
  @Override
  public MutableJsonObject fetchConfig(String folderName, String fileName) throws IOException
  {
    URL configUrl = getLogUrl(folderName, fileName);
    
    log_.debug("fetch " + configUrl);
    
    try(InputStream in =getUrl(folderName, fileName).openStream())
    {
      ObjectMapper mapper = new ObjectMapper();
      
      JsonNode tree = mapper.readTree(in);
      
      JsonNode type = tree.get(TYPE);
      
      if(!TYPE_FILE.equals(type.asText()))
        throw new IllegalArgumentException("Unable to fetchConfig from " + configUrl + ", expected a file but found a " + type);
      
      JsonNode content = tree.get("content");
      
      if(content == null || !content.isTextual())
        throw new IllegalArgumentException("Unable to fetchConfig from " + configUrl + ", there is no content node in the JSON there");
      
      byte[] bytes = Base64.decodeBase64(content.asText());
      
      JsonNode config = mapper.readTree(bytes);
      
      if(config instanceof ObjectNode)
      {
        return JacksonAdaptor.adaptObject((ObjectNode)config);
      }
      throw new IllegalArgumentException("Unable to fetchConfig from " + configUrl + ", this URL does not contain a JSON object");
    }
  }
  
  @Override
  public List<String> fetchFiles(String folderName)
  {
    return fetchDirItems(folderName, TYPE_FILE);
  }
  
  @Override
  public List<String> fetchDirs(String folderName)
  {
    return fetchDirItems(folderName, TYPE_DIR);
  }
  
  private List<String> fetchDirItems(String folderName, String requiredType)
  {
    List<String> result = new ArrayList<>();
    URL configUrl = getLogDirUrl(folderName);
    
    try(InputStream in =getDirUrl(folderName).openStream())
    {
      ObjectMapper mapper = new ObjectMapper();
      
      JsonNode tree = mapper.readTree(in);
      
      if(tree instanceof ArrayNode)
      {
        for(JsonNode node : tree)
        {
          JsonNode type = node.get(TYPE);
          
          if(requiredType.equals(type.asText()))
          {
            result.add(node.get("name").asText());
          }
        }
      }
      else
      {
        throw new IllegalArgumentException("Unable to fetchFiles from " + configUrl + ", received a non-array response.");
      }
    }
    catch (FileNotFoundException e)
    {
      log_.warn("No such directory \"" + folderName + "\" returning empty list.");
    }
    catch (IOException e)
    {
      throw new IllegalArgumentException("Unable to fetchFiles from " + configUrl + ", this URL is not readable", e);
    }
    
    return result;
  }
}

/******************************************************************************
 *   Copyright (c) 2012-2015 VMware, Inc. All Rights Reserved.
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *****************************************************************************/
package com.vmware.bdd.cli.rest;

import com.vmware.bdd.apitypes.Connect;
import com.vmware.bdd.apitypes.TaskRead;
import com.vmware.bdd.apitypes.TaskRead.Status;
import com.vmware.bdd.apitypes.TaskRead.Type;
import com.vmware.bdd.cli.auth.LoginClient;
import com.vmware.bdd.cli.auth.LoginResponse;
import com.vmware.bdd.cli.commands.CommandsUtils;
import com.vmware.bdd.cli.commands.Constants;
import com.vmware.bdd.cli.commands.CookieCache;
import com.vmware.bdd.exception.BddException;
import com.vmware.bdd.utils.CommonUtil;
import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.log4j.Logger;
import org.fusesource.jansi.AnsiConsole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * RestClient provides common rest apis required by resource operations.
 *
 */
@Component
public class RestClient {

   static final Logger logger = Logger.getLogger(RestClient.class);

   private String hostUri;

   @Autowired
   private RestTemplate client;

   @Autowired
   private LoginClient loginClient;

   @Autowired
   private HttpClient httpClient;

   private RestClient() {
   }

   public String getContentAsString(String path) {
      String uri = hostUri + path;
      logger.debug("getContentAsString @ " + uri);

      HttpGet getRequest = new HttpGet(uri);

      String cookieInfo = readCookieInfo();
      getRequest.setHeader("Cookie", cookieInfo == null ? "" : cookieInfo);

      try {
         HttpResponse response = httpClient.execute(getRequest);

         int responseCode = response.getStatusLine().getStatusCode();

         InputStream contentStream = response.getEntity().getContent();
         String contentAsString = IOUtils.toString(contentStream, "UTF-8");

         logger.debug("content as string: " + contentAsString);
         if(responseCode != org.apache.http.HttpStatus.SC_OK) {
            HttpStatus status = HttpStatus.valueOf(responseCode);
            RestErrorHandler.handleHttpErrCode(status, contentAsString);
         }

         return contentAsString;
      } catch (IOException e) {
         throw new CliRestException("HTTP Response Error: " + e.getMessage());
      } finally {
         getRequest.releaseConnection();
      }
   }

   /**
    * connect to a Serengeti server
    *
    * @param host
    *           host url with optional port
    * @param username
    *           serengeti login user name
    * @param password
    *           serengeti password
    */
   public Connect.ConnectType connect(final String host, final String username,
         final String password) {
      String oldHostUri = hostUri;

      hostUri =
            Constants.HTTPS_CONNECTION_PREFIX + host
                  + Constants.HTTPS_CONNECTION_LOGIN_SUFFIX;

      Connect.ConnectType connectType = null;
      try {
         LoginResponse response = loginClient.login(hostUri, username, password);
         //200
         if (response.getResponseCode() == HttpStatus.OK.value()) {
            if(CommonUtil.isBlank(response.getSessionId())) {
               if (isConnected()) {
                  System.out.println(Constants.CONNECTION_ALREADY_ESTABLISHED);
                  connectType = Connect.ConnectType.SUCCESS;
               } else {
                  System.out.println(Constants.CONNECT_FAILURE_NO_SESSION_ID);
                  connectType = Connect.ConnectType.ERROR;
               }
            } else {
               //normal response
               writeCookieInfo(response.getSessionId());
               System.out.println(Constants.CONNECT_SUCCESS);
               connectType = Connect.ConnectType.SUCCESS;
            }
         }
         //401
         else if(response.getResponseCode() == HttpStatus.UNAUTHORIZED.value()) {
            System.out.println(Constants.CONNECT_UNAUTHORIZATION_CONNECT);
            //recover old hostUri
            hostUri = oldHostUri;
            connectType = Connect.ConnectType.UNAUTHORIZATION;
         }
         //500
         else if(response.getResponseCode() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            System.out.println(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
            connectType = Connect.ConnectType.ERROR;
         } else {
            //error
            System.out.println(
                  String.format(
                  Constants.UNSUPPORTED_HTTP_RESPONSE_CODE, response.getResponseCode()));
            //recover old hostUri
            hostUri = oldHostUri;
            connectType = Connect.ConnectType.ERROR;
         }
      } catch (Exception e) {
         System.out.println(Constants.CONNECT_FAILURE + ": "
               + (CommandsUtils.getExceptionMessage(e)));
         connectType = Connect.ConnectType.ERROR;
      }

      return connectType;
   }

   private boolean isConnected() {
      return (hostUri != null) && (!CommandsUtils.isBlank(readCookieInfo()));
   }

   /**
    * Disconnect the session
    */
   public void disconnect() {
      try {
         checkConnection();

         getContentAsString(Constants.REST_PATH_LOGOUT);
      } catch (CliRestException cliRestException) {
         if (cliRestException.getStatus() == HttpStatus.UNAUTHORIZED) {
            writeCookieInfo("");
         }
      } catch (Exception e) {
         System.out.println(Constants.DISCONNECT_FAILURE + ": "
               + CommandsUtils.getExceptionMessage(e));
      }
   }

   private void writeCookieInfo(String cookie) {
      CookieCache.put(CookieCache.COOKIE, cookie);
   }

   private String readCookieInfo() {
      String cookieValue = "";
      cookieValue = CookieCache.get(CookieCache.COOKIE);
      return cookieValue;
   }

   private <T> ResponseEntity<T> restGetById(final String path,
         final String id, final Class<T> respEntityType,
         final boolean hasDetailQueryString) {
      String targetUri =
            hostUri + Constants.HTTPS_CONNECTION_API + path + "/" + id;
      if (hasDetailQueryString) {
         targetUri += Constants.QUERY_DETAIL;
      }
      return restGetByUri(targetUri, respEntityType);
   }

   private <T> ResponseEntity<T> restGet(final String path,
         final Class<T> respEntityType, final boolean hasDetailQueryString) {
      String targetUri = hostUri + Constants.HTTPS_CONNECTION_API + path;
      if (hasDetailQueryString) {
         targetUri += Constants.QUERY_DETAIL;
      }
      return restGetByUri(targetUri, respEntityType);
   }

   private <T> ResponseEntity<T> restGetByUri(String uri,
         Class<T> respEntityType) {
      HttpHeaders headers = buildHeaders();
      HttpEntity<String> entity = new HttpEntity<String>(headers);
      return client.exchange(uri, HttpMethod.GET, entity, respEntityType);
   }

   private HttpHeaders buildHeaders(boolean withCookie) {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      List<MediaType> acceptedTypes = new ArrayList<MediaType>();
      acceptedTypes.add(MediaType.APPLICATION_JSON);
      acceptedTypes.add(MediaType.TEXT_HTML);
      headers.setAccept(acceptedTypes);

      if (withCookie) {
         String cookieInfo = readCookieInfo();
         headers.add("Cookie", cookieInfo == null ? "" : cookieInfo);
      }
      return headers;
   }

   private HttpHeaders buildHeaders() {
      return buildHeaders(true);
   }

   /**
    * Create an object through rest apis
    *
    * @param entity
    *           the creation content
    * @param path
    *           the rest url
    * @param verb
    *           the http method
    * @param prettyOutput
    *           output callback
    */
   public void createObject(Object entity, final String path,
         final HttpMethod verb, PrettyOutput... prettyOutput) {
      checkConnection();
      try {
         if (verb == HttpMethod.POST) {
            ResponseEntity<String> response = restPost(path, entity);
            if (!validateAuthorization(response)) {
               return;
            }
            processResponse(response, HttpMethod.POST, prettyOutput);
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }

      } catch (Exception e) {
         if(e instanceof CliRestException) {
            throw (CliRestException)e;
         }

         if(e instanceof BddException) {
            throw (BddException)e;
         }

         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   private ResponseEntity<String> restPost(String path, Object entity) {
      String targetUri = hostUri + Constants.HTTPS_CONNECTION_API + path;

      HttpHeaders headers = buildHeaders();
      HttpEntity<Object> postEntity = new HttpEntity<Object>(entity, headers);

      return client.exchange(targetUri, HttpMethod.POST, postEntity,
            String.class);
   }

   /*
    * Will process normal response with/without a task location header
    */
   private TaskRead processResponse(ResponseEntity<String> response,
         HttpMethod verb, PrettyOutput... prettyOutput) throws Exception {

      HttpStatus responseStatus = response.getStatusCode();
      if (responseStatus == HttpStatus.ACCEPTED) {//Accepted with task in the location header
         //get task uri from response to trace progress
         HttpHeaders headers = response.getHeaders();
         URI taskURI = headers.getLocation();
         String[] taskURIs = taskURI.toString().split("/");
         String taskId = taskURIs[taskURIs.length - 1];

         TaskRead taskRead;
         int oldProgress = 0;
         Status oldTaskStatus = null;
         Status taskStatus = null;
         int progress = 0;
         do {
            ResponseEntity<TaskRead> taskResponse =
                  restGetById(Constants.REST_PATH_TASK, taskId, TaskRead.class,
                        false);

            //task will not return exception as it has status
            taskRead = taskResponse.getBody();

            progress = (int) (taskRead.getProgress() * 100);
            taskStatus = taskRead.getStatus();

            //fix cluster deletion exception
            Type taskType = taskRead.getType();
            if ((taskType == Type.DELETE) && (taskStatus == TaskRead.Status.COMPLETED)) {
               clearScreen();
               System.out.println(taskStatus + " " + progress + "%\n");
               break;
            }

            if (taskType == Type.SHRINK && !taskRead.getFailNodes().isEmpty()) {
               throw new CliRestException(taskRead.getFailNodes().get(0).getErrorMessage());
            }

            if ((prettyOutput != null && prettyOutput.length > 0 && prettyOutput[0].isRefresh(true))
                  || oldTaskStatus != taskStatus
                  || oldProgress != progress) {
               //clear screen and show progress every few seconds
               clearScreen();
               //output completed task summary first in the case there are several related tasks
               if (prettyOutput != null && prettyOutput.length > 0
                     && prettyOutput[0].getCompletedTaskSummary() != null) {
                  for (String summary : prettyOutput[0]
                        .getCompletedTaskSummary()) {
                     System.out.println(summary + "\n");
                  }
               }
               System.out.println(taskStatus + " " + progress + "%\n");

               if (prettyOutput != null && prettyOutput.length > 0) {
                  // print call back customize the detailed output case by case
                  prettyOutput[0].prettyOutput();
               }

               if (oldTaskStatus != taskStatus || oldProgress != progress) {
                  oldTaskStatus = taskStatus;
                  oldProgress = progress;
                  if (taskRead.getProgressMessage() != null) {
                     System.out.println(taskRead.getProgressMessage());
                  }
               }
            }
            try {
               Thread.sleep(3 * 1000);
            } catch (InterruptedException ex) {
               //ignore
            }
         } while (taskStatus != TaskRead.Status.COMPLETED
               && taskStatus != TaskRead.Status.FAILED
               && taskStatus != TaskRead.Status.ABANDONED
               && taskStatus != TaskRead.Status.STOPPED);

         String errorMsg = taskRead.getErrorMessage();
         if (!taskRead.getStatus().equals(TaskRead.Status.COMPLETED)) {
            throw new CliRestException(errorMsg);
         } else { //completed
            if (taskRead.getType().equals(Type.VHM)) {
               logger.info("task type is vhm");
               Thread.sleep(5*1000);
               if (prettyOutput != null && prettyOutput.length > 0
                     && prettyOutput[0].isRefresh(true)) {
                  //clear screen and show progress every few seconds
                  clearScreen();
                  System.out.println(taskStatus + " " + progress + "%\n");

                  // print call back customize the detailed output case by case
                  if (prettyOutput != null && prettyOutput.length > 0) {
                     prettyOutput[0].prettyOutput();
                  }
               }
            } else {
               return taskRead;
            }
         }
      }
      return null;
   }

   private void clearScreen() {
      AnsiConsole.systemInstall();
      String separator = "[";
      char ESC = 27;
      String clearScreen = "2J";
      System.out.print(ESC + separator + clearScreen);
      AnsiConsole.systemUninstall();
   }

   /**
    * Generic method to get an object by id
    *
    * @param id
    * @param entityType
    *           the object type
    * @param path
    *           the rest url
    * @param verb
    *           the http method
    * @param detail
    *           flag to retrieve detailed information or not
    * @return the object
    */
   public <T> T getObject(final String id, Class<T> entityType,
         final String path, final HttpMethod verb, final boolean detail) {
      checkConnection();
      try {
         if (verb == HttpMethod.GET) {
            ResponseEntity<T> response =
                  restGetById(path, id, entityType, detail);
            if (!validateAuthorization(response)) {
               return null;
            }
            T objectRead = response.getBody();

            return objectRead;
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }
      } catch (Exception e) {
         if(e instanceof CliRestException) {
            throw (CliRestException)e;
         }

         if(e instanceof BddException) {
            throw (BddException)e;
         }

         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   public <T> T getObject(final String path, Class<T> entityType, final HttpMethod verb, Object body) {
      checkConnection();
      try {
         if (verb == HttpMethod.POST) {
            ResponseEntity<T> response = this.restQueryWithBody(path, entityType, body);
            if (!validateAuthorization(response)) {
               return null;
            }
            T objectRead = response.getBody();

            return objectRead;
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }
      } catch (Exception e) {
         if(e instanceof CliRestException) {
            throw (CliRestException)e;
         }

         if(e instanceof BddException) {
            throw (BddException)e;
         }

         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   /**
    * Method to get by path
    *
    * @param entityType
    * @param path
    * @param verb
    * @param detail
    * @return
    */
   public <T> T getObjectByPath(Class<T> entityType, final String path,
         final HttpMethod verb, final boolean detail) {
      checkConnection();

      try {
         if (verb == HttpMethod.GET) {
            ResponseEntity<T> response = restGet(path, entityType, detail);

            T objectRead = response.getBody();

            return objectRead;
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }
      } catch (Exception e) {
         if(e instanceof CliRestException) {
            throw (CliRestException)e;
         }

         if(e instanceof BddException) {
            throw (BddException)e;
         }

         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   /**
    * Generic method to get all objects of a type
    *
    * @param entityType
    *           object type
    * @param path
    *           the rest url
    * @param verb
    *           the http method
    * @param detail
    *           flag to retrieve detailed information or not
    * @return the objects
    */
   public <T> T getAllObjects(final Class<T> entityType, final String path,
         final HttpMethod verb, final boolean detail) {
      checkConnection();
      try {
         if (verb == HttpMethod.GET) {
            ResponseEntity<T> response = restGet(path, entityType, detail);
            if (!validateAuthorization(response)) {
               return null;
            }
            T objectsRead = response.getBody();

            return objectsRead;
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }
      } catch (Exception e) {
         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   /**
    * Delete an object by id
    *
    * @param id
    * @param path
    *           the rest url
    * @param verb
    *           the http method
    * @param prettyOutput
    *           utput callback
    */
   public void deleteObject(final String id, final String path,
         final HttpMethod verb, PrettyOutput... prettyOutput) {
      checkConnection();
      try {
         if (verb == HttpMethod.DELETE) {
            ResponseEntity<String> response = restDelete(path, id);
            if (!validateAuthorization(response)) {
               return;
            }
            processResponse(response, HttpMethod.DELETE, prettyOutput);
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }

      } catch (Exception e) {
         if(e instanceof CliRestException) {
            throw (CliRestException)e;
         }

         if(e instanceof BddException) {
            throw (BddException)e;
         }

         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   private ResponseEntity<String> restDelete(String path, String id) {
      String targetUri =
            hostUri + Constants.HTTPS_CONNECTION_API + path + "/" + id;

      HttpHeaders headers = buildHeaders();
      HttpEntity<String> entity = new HttpEntity<String>(headers);

      return client
            .exchange(targetUri, HttpMethod.DELETE, entity, String.class);
   }

   private void checkConnection() {
      if (hostUri == null) {
         throw new CliRestException(Constants.NEED_CONNECTION);
      } else if (CommandsUtils.isBlank(readCookieInfo())) {
         throw new CliRestException(Constants.CONNECT_CHECK_LOGIN);
      }
   }

   /**
    * process requests with query parameters
    *
    * @param id
    * @param path
    *           the rest url
    * @param verb
    *           the http method
    * @param queryStrings
    *           required query strings
    * @param prettyOutput
    *           output callback
    */
   public void actionOps(final String id, final String path,
         final HttpMethod verb, final Map<String, String> queryStrings,
         PrettyOutput... prettyOutput) {
      checkConnection();
      try {
         if (verb == HttpMethod.PUT) {
            ResponseEntity<String> response =
                  restActionOps(path, id, queryStrings);
            if (!validateAuthorization(response)) {
               return;
            }
            processResponse(response, HttpMethod.PUT, prettyOutput);
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }

      } catch (Exception e) {
         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   private ResponseEntity<String> restActionOps(String path, String id,
         Map<String, String> queryStrings) {
      String targetUri =
            hostUri + Constants.HTTPS_CONNECTION_API + path + "/" + id;
      if (queryStrings != null) {
         targetUri = targetUri + buildQueryStrings(queryStrings);
      }
      HttpHeaders headers = buildHeaders();
      HttpEntity<String> entity = new HttpEntity<String>(headers);

      return client.exchange(targetUri, HttpMethod.PUT, entity, String.class);
   }

   private String buildQueryStrings(Map<String, String> queryStrings) {
      StringBuilder stringBuilder = new StringBuilder("?");

      Set<Entry<String, String>> entryset = queryStrings.entrySet();
      for (Entry<String, String> entry : entryset) {
         stringBuilder.append(entry.getKey() + "=" + entry.getValue() + "&");
      }
      int length = stringBuilder.length();
      if (stringBuilder.charAt(length - 1) == '&') {
         return stringBuilder.substring(0, length - 1);
      } else {
         return stringBuilder.toString();
      }
   }

   /**
    * Update an object
    *
    * @param entity
    *           the updated content
    * @param path
    *           the rest url
    * @param verb
    *           the http method
    * @param prettyOutput
    *           output callback
    */
   public void update(Object entity, final String path, final HttpMethod verb,
         PrettyOutput... prettyOutput) {
      checkConnection();
      try {
         if (verb == HttpMethod.PUT) {
            ResponseEntity<String> response = restUpdate(path, entity);
            if (!validateAuthorization(response)) {
               return;
            }
            processResponse(response, HttpMethod.PUT, prettyOutput);
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }

      } catch (Exception e) {
         if(e instanceof CliRestException) {
            throw (CliRestException)e;
         }

         if(e instanceof BddException) {
            throw (BddException)e;
         }

         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   public TaskRead updateWithReturn(Object entity, final String path, final HttpMethod verb, PrettyOutput... prettyOutput) {
      checkConnection();
      try {
         if (verb == HttpMethod.PUT) {
            ResponseEntity<String> response = restUpdate(path, entity);
            if (!validateAuthorization(response)) {
               return null;
            }
            return processResponse(response, HttpMethod.PUT, prettyOutput);
         } else {
            throw new Exception(Constants.HTTP_VERB_ERROR);
         }
      } catch (Exception e) {
         throw new CliRestException(CommandsUtils.getExceptionMessage(e));
      }
   }

   public void updateWithQueryStrings(Object entity, final String path, Map<String, String> queryStrings,
                                      final HttpMethod verb, PrettyOutput... prettyOutput) {
      String queryString = "";
      if (queryStrings != null && !queryStrings.isEmpty()) {
         queryString = buildQueryStrings(queryStrings);
      }
      update(entity, path + queryString, verb, prettyOutput);
   }

   private ResponseEntity<String> restUpdate(String path, Object entityName) {
      String targetUri = hostUri + Constants.HTTPS_CONNECTION_API + path;

      HttpHeaders headers = buildHeaders();
      HttpEntity<Object> entity = new HttpEntity<Object>(entityName, headers);

      return client.exchange(targetUri, HttpMethod.PUT, entity, String.class);
   }

   private <T> ResponseEntity<T> restQueryWithBody(String path, Class<T> entityType, Object body) {
      String targetUri = hostUri + Constants.HTTPS_CONNECTION_API + path;
      HttpHeaders headers = buildHeaders();
      HttpEntity<Object> entity = new HttpEntity<Object>(body, headers);
      return client.exchange(targetUri, HttpMethod.POST, entity, entityType);
   }

   @SuppressWarnings("rawtypes")
   private boolean validateAuthorization(ResponseEntity response) {
      if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
         System.out.println(Constants.CONNECT_UNAUTHORIZATION_OPT);
         return false;
      }
      return true;
   }
}

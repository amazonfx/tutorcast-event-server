package com.tutorcast.roommodules;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.mina.util.Base64;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import scala.Some;
import thrift.Skeletons;

import com.etherpad.easysync2.AttribPool;
import com.etherpad.easysync2.Changeset;
import com.twitter.finagle.Service;
import com.twitter.finagle.builder.ClientBuilder;
import com.twitter.finagle.stats.NullStatsReceiver;
import com.twitter.finagle.thrift.ClientId;
import com.twitter.finagle.thrift.ThriftClientFramedCodecFactory;
import com.twitter.finagle.thrift.ThriftClientRequest;
import com.twitter.util.FutureEventListener;

import net.user1.union.api.Client;
import net.user1.union.api.Module;
import net.user1.union.core.attribute.Attribute;
import net.user1.union.core.context.ModuleContext;
import net.user1.union.core.event.RoomEvent;
import net.user1.union.core.exception.AttributeException;

public class WhiteboardModule implements Module, Runnable {
  private boolean replayMode = false;
  private Skeletons.FinagledClient serviceClient = null;
  private ModuleContext context;
  private static final int CHUNK_SIZE = 45000;
  private int replayBufferPointer = 0;
  private boolean bufferFinalized = false;
  private boolean bufferCleared = false;
  public long createdTime = 0;
  private HashMap<String, HashMap<String, String>> userData = new HashMap();
  private HashMap<String, HashMap> unpaidUserWarnings = new HashMap();
  
  private Thread saveReplayThread;
  private Lock sendLock = new ReentrantLock(true);
  private Condition sendComplete = sendLock.newCondition();
  private Condition finalizeComplete = sendLock.newCondition();
  private Condition clearComplete = sendLock.newCondition();

  private static final String COMMAND_UPDATE_EVENT = "COMMAND_UPDATE_EVENT";
  private static final String REPLAY_EVENT = "REPLAY_EVENT";
  private static final String TEXT_UPDATE_EVENT = "TEXT_UPDATE_EVENT";
  private static final String PAGE_CHANGE_EVENT = "PAGE_CHANGE_EVENT";
  private static final String PDF_UPDATE_EVENT = "PDF_UPDATE_EVENT";
  private static final String CHAT_UPDATE_EVENT = "CHAT_UPDATE_EVENT";
  private static final String REGISTER_USER_EVENT = "REGISTER_USER_EVENT";
  private static final String NOTIFY_USER_PAID_EVENT = "NOTIFY_USER_PAID_EVENT";

  public static final String WEB_URL = "http://localhost";
  public static final Integer SLEEP_INTERVAL = 60000;

  private HashMap<Integer, RevisionManager> revisionManagerMap = new HashMap();
  private int noClientCount = 0;

  private int currentPage = 1;
  private int totalPages = 1;

  @Override
  public boolean init(ModuleContext ctx) {
    context = ctx;
    context.getRoom().addEventListener(RoomEvent.ADD_CLIENT, this, "onAddClient");
    context.getRoom().addEventListener(RoomEvent.REMOVE_CLIENT, this, "onRemoveClient");
    context.getRoom().addEventListener(RoomEvent.MODULE_MESSAGE, this, "onModuleMessage");

    ThriftClientFramedCodecFactory codecFactory =
        new ThriftClientFramedCodecFactory(new Some<ClientId>(new ClientId(context.getRoom()
            .getQualifiedID())));
    Service<ThriftClientRequest, byte[]> client =
        ClientBuilder.safeBuild(ClientBuilder.get().hosts(new InetSocketAddress("localhost", 8080))
            .codec(codecFactory).hostConnectionLimit(100));
    serviceClient =
        new Skeletons.FinagledClient(client, new TBinaryProtocol.Factory(), "",
            new NullStatsReceiver());
    createdTime = new Date().getTime();
    revisionManagerMap.put(currentPage, new RevisionManager(this, currentPage));
    return true;
  }

  @Override
  public void shutdown() {
    try {
      saveReplayThread = null;
      context.getRoom().removeEventListener(RoomEvent.ADD_CLIENT, this, "onAddClient");
      context.getRoom().removeEventListener(RoomEvent.REMOVE_CLIENT, this, "onRemoveClient");
      context.getRoom().removeEventListener(RoomEvent.MODULE_MESSAGE, this, "onModuleMessage");
      String historyID = "history-" + context.getRoom().getQualifiedID();
      context.getRoom().removeAttribute(historyID);
    } catch (AttributeException e) {
      e.printStackTrace();
    }
  }

  private void warnUnpaid(HashMap unpaid, Client c) {
    Integer elapsed = (Integer)unpaid.get("timeElapsed");
    Integer level = (Integer)unpaid.get("level") ;
    elapsed+=SLEEP_INTERVAL;
    if (elapsed >= SLEEP_INTERVAL*10 || level >= 2){
     // System.err.println("KICKING UNPAID USER");
      c.sendMessage("KICK_UNPAID", "BLEH");
      unpaid.put("level", 2);
    } else if (elapsed >= SLEEP_INTERVAL*5 && level < 1){
     // System.err.println("WARNING UNPAID USER:");
      try {
        JSONObject warning = new JSONObject();
        warning.put("level", 5);
        c.sendMessage("WARN_UNPAID", warning.toString());
      } catch (JSONException e) {
        e.printStackTrace();
      }
      unpaid.put("level", 1);
    }
    unpaid.put("timeElapsed", elapsed);
    unpaidUserWarnings.put(c.getClientID(), unpaid);
  }
  @Override
  public void run() {
    while (noClientCount < 3 || replayBufferPointer < getCurrentBuffer().length()) {
      try {
        Thread.sleep(SLEEP_INTERVAL);
        if (context.getRoom().getNumClients() <= 0) {
          
          ++noClientCount;
        }
        
        Set<Client> clientSet = context.getRoom().getClients();
        for (Client c:clientSet){
          String cid = c.getClientID();
          HashMap unpaid = unpaidUserWarnings.get(cid); 
          if (unpaid == null){
            continue;
          }
          warnUnpaid(unpaid, c);
        }
        
        if (replayBufferPointer >= getCurrentBuffer().length()) {
          continue;
        } else {
          try {
            sendLock.lock();
            flushReplayBuffer();
            sendComplete.await();
          } finally {
            sendLock.unlock();
          }
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    // send buffer content to s3, clear redis
    if (!replayMode) {
      updateClassStatus();
      for (int i = 0; i < 5; i++) {
        try {
          if (!bufferFinalized) {
            sendLock.lock();
            finalizeReplayBuffer();
            finalizeComplete.await();
          }
          if (bufferFinalized && !bufferCleared) {
            clearReplayBuffer();
            clearComplete.await();
            break;
          }
          Thread.sleep(20000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        } finally {
          sendLock.unlock();
        }
      }
      
      //download archives
      for (int i = 0; i < 5; i++) {
        Boolean status = signalDownloadArchive();
        if (status != null && status){
          break;
        }else {
          try {
            Thread.sleep(60000);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }
    }
    // shutdown room if no clients for 3 consecutive runs
    context.getRoom().shutdownRoom();
  }
  
  public void handleUserPaid(RoomEvent ev) {
    String userId = ev.getMessage().getArg("userId");
    //System.err.println("RECEIVED USERID:"+userId);
    String removeClient = null;
    for (String k:unpaidUserWarnings.keySet()){
      HashMap u = unpaidUserWarnings.get(k);
      if (((String)u.get("userId")).trim().equalsIgnoreCase(userId)){
        removeClient = k;
        break;
      }
    }
    //System.err.println("REMOVING CLIENT FROM UNPAID:"+removeClient);
    unpaidUserWarnings.remove(removeClient);
    
    for (String k:userData.keySet()){
      HashMap u = userData.get(k);
      if (((String)u.get("userId")).trim().equalsIgnoreCase(userId)){
        u.put("paid", true);
        break;
      }
    }
    syncUserData(ev);
  }

  public void handleRegisterUser(RoomEvent ev) {
    String clientId = ev.getClient().getClientID();
    String userInfo = ev.getMessage().getArg("userInfo");
    HashMap userMap = new HashMap();
    try {
      JSONObject userObject = new JSONObject(userInfo);
      userMap.put("userEmail", userObject.getString("userEmail"));
      userMap.put("userId", userObject.getString("userId"));
      userMap.put("username", userObject.getString("userName"));
      userMap.put("profilePic", userObject.getString("profilePic"));
      
      Boolean paid = userObject.getBoolean("paid");
      String userId = userObject.getString("userId");
      
      //rewrite unpaid map
      HashMap<String, String> clientsMap = new HashMap();
      for (String k:unpaidUserWarnings.keySet()){
        HashMap u = unpaidUserWarnings.get(k);
        if (((String)u.get("userId")).trim().equalsIgnoreCase(userId)){
          clientsMap.put(clientId, k);
        }
      }
      if (!clientsMap.isEmpty()) {
        String oldCid = clientsMap.get(clientId);
        HashMap oldWarning = unpaidUserWarnings.get(oldCid);
        unpaidUserWarnings.remove(oldCid);
        unpaidUserWarnings.put(clientId,oldWarning);
      }
      if (!paid){
        HashMap existingWarning = unpaidUserWarnings.get(clientId);
        if (existingWarning != null){
          warnUnpaid(existingWarning, ev.getClient());
        } else {
          HashMap warningMap = new HashMap();
          warningMap.put("level", new Integer(0));
          warningMap.put("userId", userId);
          warningMap.put("timeElapsed", new Integer(0));
          unpaidUserWarnings.put(clientId, warningMap);
          //System.err.println("SENDING UNPAID WARNIG");
          JSONObject warning = new JSONObject();
          warning.put("level", 10);
          ev.getClient().sendMessage("WARN_UNPAID", warning.toString());
        }    
      } else {
        unpaidUserWarnings.remove(clientId);
      }
      userMap.put("paid", paid);
    } catch (JSONException e) {
      e.printStackTrace();
    }
    userData.put(clientId, userMap);
    syncUserData(ev);
  }

  public void onAddClient(RoomEvent ev) {
    noClientCount = 0;
    Boolean classEnded = getClassStatus();
    
    int currentMemberCount = context.getRoom().getNumClients();
    if (saveReplayThread == null && currentMemberCount < 2) {
      saveReplayThread = new Thread(this);
      saveReplayThread.start();
    }
    
    syncRoomClients(ev.getClient());
    
    if (classEnded) {
      ev.getClient().sendMessage("END", "Class Ended");
    }
    
    Set<Client> syncClientList = new HashSet();
    syncClientList.add(ev.getClient());
    syncStateForPage(currentPage, syncClientList, true);

    initClientTextState(ev.getClient(), currentPage);
  }

  public void onModuleMessage(RoomEvent ev) {
    String messageType = ev.getMessage().getMessageName().trim();
    if (messageType.equalsIgnoreCase(COMMAND_UPDATE_EVENT)) {
      handleCommandUpdateEvent(ev);
    } else if (messageType.equalsIgnoreCase(REPLAY_EVENT)) {
      handleReplayEvent(ev);
    } else if (messageType.equalsIgnoreCase(TEXT_UPDATE_EVENT)) {
      handleTextUpdateEvent(ev);
    } else if (messageType.equalsIgnoreCase(PAGE_CHANGE_EVENT)) {
      handlePageChangeEvent(ev);
    } else if (messageType.equalsIgnoreCase(PDF_UPDATE_EVENT)) {
      handlePDFUpdateEvent(ev);
    } else if (messageType.equalsIgnoreCase(CHAT_UPDATE_EVENT)) {
      handleChatUpdateEvent(ev);
    } else if (messageType.equalsIgnoreCase(REGISTER_USER_EVENT)) {
      handleRegisterUser(ev);
    } else if (messageType.equalsIgnoreCase(NOTIFY_USER_PAID_EVENT)) {
      handleUserPaid(ev);
    }
    
  }

  

  

  public void onRemoveClient(RoomEvent ev) {
    String clientId = ev.getClient().getClientID();
    userData.remove(clientId);
    syncUserData(ev);
  }


  public void appendToBuffer(String newCommands) {
    if (newCommands.isEmpty()) {
      return;
    }
    StringBuffer buffer = getCurrentBuffer();
    if (buffer.length() > 0) {
      buffer.append(",");
    }
    buffer.append(newCommands);

    String historyID = "history-" + context.getRoom().getQualifiedID();
    Attribute history = context.getRoom().getAttribute(historyID);
    try {
      history.setValue(buffer);
    } catch (AttributeException e) {
      e.printStackTrace();
    }
  }

  public void broadcastChangeset(Integer serverRev, String sendingClientId, Changeset changeset,
      AttribPool pool, Integer page) {
    Long currentTime = new Date().getTime();
    Long elapsedMills = currentTime - createdTime;
    int csStart = 0;
    int csEnd = 0;
    String cs = new String(Base64.encodeBase64(changeset.pack().getBytes()));

    StringBuffer commandBuffer = new StringBuffer();
    commandBuffer.append(elapsedMills);
    commandBuffer.append("|T|");
    commandBuffer.append(page + "|");
    commandBuffer.append(sendingClientId + "|");
    commandBuffer.append(serverRev + "|");
    int prefixLength = commandBuffer.length() + (commandBuffer.length() + "").length() + 1;
    csStart = prefixLength + (prefixLength + "").length() + 1;
    csEnd = csStart + cs.length();
    commandBuffer.append(csStart + "|");
    commandBuffer.append(csEnd + "|");
    commandBuffer.append(cs);
    appendToBuffer(commandBuffer.toString());

    Set<Client> clientList = context.getRoom().getClients();
    JSONObject updateMsg = new JSONObject();
    JSONObject ackMsg = new JSONObject();
    try {
      updateMsg.put("senderID", sendingClientId);
      updateMsg.put("revID", serverRev);
      updateMsg.put("changeset", cs);
      updateMsg.put("pool", pool.toJsonable());
      updateMsg.put("page", page);
      updateMsg.put("senderID", sendingClientId);
      
      ackMsg.put("revID", serverRev);
      ackMsg.put("changeset", cs);
      ackMsg.put("pool", pool.toJsonable());
      ackMsg.put("page", page);
    } catch (JSONException e) {
      e.printStackTrace();
    }
    for (Client c : clientList) {
      if (!c.getClientID().trim().equals(sendingClientId)) {
        //System.err.println("broadcasting revisionID:"+serverRev+": to cliendID:"+c.getClientID());
        c.sendMessage("UPDATE_TEXT", updateMsg.toString());
      }
    }

    for (Client c : clientList) {
      if (c.getClientID().trim().equals(sendingClientId)) {
       //System.err.println("acknowledging revisionID:"+serverRev+": to clientID:"+c.getClientID());
        c.sendMessage("UPDATE_TEXT_ACK", ackMsg.toString());
      }
    }
  }

  public void addPageChangeToHistory() {
    Long currentTime = new Date().getTime();
    Long elapsedMills = currentTime - createdTime;
    StringBuffer commandBuffer = new StringBuffer();
    commandBuffer.append(elapsedMills);
    commandBuffer.append("|CP|");
    commandBuffer.append(currentPage + "|");
    commandBuffer.append(totalPages);
    appendToBuffer(commandBuffer.toString());

  }

  public void clearReplayBuffer() {
    serviceClient.clear("replay-" + context.getRoom().getQualifiedID()).addEventListener(
        new FutureEventListener<Boolean>() {
          @Override
          public void onFailure(Throwable e) {
            try {
              e.printStackTrace();
              sendLock.lock();
              clearComplete.signal();
            } finally {
              sendLock.unlock();
            }
          }

          @Override
          public void onSuccess(Boolean result) {
            if (result) {
              bufferCleared = true;
            }
            try {
              sendLock.lock();
              clearComplete.signal();
            } finally {
              sendLock.unlock();
            }
          }
        });
  }

  // finalize replay buffer and move to s3
  public void finalizeReplayBuffer() {
    serviceClient.finalize("replay-" + context.getRoom().getQualifiedID()).addEventListener(
        new FutureEventListener<Boolean>() {
          @Override
          public void onFailure(Throwable e) {
            try {
              e.printStackTrace();
              sendLock.lock();
              finalizeComplete.signal();
            } finally {
              sendLock.unlock();
            }
          }

          @Override
          public void onSuccess(Boolean result) {
            if (result) {
              bufferFinalized = true;
            }
            try {
              sendLock.lock();
              finalizeComplete.signal();
            } finally {
              sendLock.unlock();
            }
          }
        });
  }


  // flush to redis
  public void flushReplayBuffer() {
    StringBuffer sendBuffer = getCurrentBuffer();
    // advance past separating comma
    if (sendBuffer.substring(replayBufferPointer, replayBufferPointer + 1).trim().equals(",")) {
      ++replayBufferPointer;
    }
    final String sendCommands = sendBuffer.substring(replayBufferPointer);
    serviceClient.append("replay-" + context.getRoom().getQualifiedID(), sendCommands)
        .addEventListener(new FutureEventListener<Boolean>() {
          @Override
          public void onFailure(Throwable e) {
            try {
              //System.err.println("FLUSH FAILED FUCK !!!!");
              e.printStackTrace();
              sendLock.lock();
              sendComplete.signal();
            } finally {
              sendLock.unlock();
            }
          }

          @Override
          public void onSuccess(Boolean result) {
            //System.err.println("FLUSH SUCCESS CALLED WITH RESULT:"+result);
            if (result) {
              replayBufferPointer += sendCommands.length();
            }
            try {
              sendLock.lock();
              sendComplete.signal();
            } finally {
              sendLock.unlock();
            }
          }
        });
  }

  private Boolean getClassStatus() {
    HttpClient httpclient = new DefaultHttpClient();
    try {
        String url = WEB_URL + "/getClassEnded/"+context.getRoom().getQualifiedID();
        HttpGet httpget = new HttpGet(url);
        // Create a response handler
        ResponseHandler<String> responseHandler = new BasicResponseHandler();
        String responseBody = httpclient.execute(httpget, responseHandler);
        //System.err.println("DOWNLOAD GETCLASS RESPONSE::"+responseBody);
        JSONObject result = new JSONObject(responseBody);
        Boolean status = result.getBoolean("success");
        if (!status){
          return true;
        }
        boolean ended = result.getBoolean("ended");
        return ended;
    } catch (Exception e) {
      e.printStackTrace();
      return true;
    } finally {
        httpclient.getConnectionManager().shutdown();
    }
  }

  public StringBuffer getCurrentBuffer() {
    try {
      String historyID = "history-" + context.getRoom().getQualifiedID();
      Attribute history = context.getRoom().getAttribute(historyID);
      if (history == null) {
        history =
            context.getRoom().setAttribute(historyID, new StringBuffer(), Attribute.SCOPE_GLOBAL,
                Attribute.FLAG_NONE);
      }
      StringBuffer sendBuffer = (StringBuffer) history.getValue();
      return sendBuffer;
    } catch (AttributeException e) {
      return null;
    }
  }

  public void handleChatUpdateEvent(RoomEvent ev) {
    String senderID = ev.getClient().getClientID();
    Set<Client> clientList = context.getRoom().getClients();
    String chatMsg = ev.getMessage().getArg("msg");
    
    Long currentTime = new Date().getTime();
    Long elapsedMills = currentTime - createdTime;
    String cmd = elapsedMills+"|CH|"+currentPage+"|"+chatMsg;
    appendToBuffer(cmd);  
    
    for (Client c : clientList) {
      if (!c.getClientID().trim().equals(senderID)) {
        try {
          JSONObject msg = new JSONObject();
          msg.put("senderId", senderID);
          msg.put("command", cmd);
          c.sendMessage("CHAT_UPDATE", msg.toString());
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    }
  }
  
  public void handleCommandUpdateEvent(RoomEvent ev) {
    String senderID = ev.getClient().getClientID();
    Set<Client> clientList = context.getRoom().getClients();
    String newCommands = ev.getMessage().getArg("command");

    // add server timestamp to commands so they can be replayed
    Long currentTime = new Date().getTime();
    Long elapsedMills = currentTime - createdTime;
    StringBuffer commandBuffer = new StringBuffer();
    String[] commandList = newCommands.split(",");
    for (String c : commandList) {
      if (commandBuffer.length() > 0) {
        commandBuffer.append(",");
      }
      commandBuffer.append(elapsedMills + "|" + c);
    }
    appendToBuffer(commandBuffer.toString());

    for (Client c : clientList) {
      if (!c.getClientID().trim().equals(senderID)) {
        try {
          JSONObject msg = new JSONObject();
          msg.put("senderId", senderID);
          msg.put("command", commandBuffer.toString());
          c.sendMessage("COMMAND_UPDATE", msg.toString());
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    }
  }

  public void handleReplayEvent(RoomEvent ev) {
    replayMode = true;
    final Client sender = ev.getClient();
    //System.err.println("HANDLE REPLAY EVENT");
    serviceClient.get("replay-" + context.getRoom().getQualifiedID()).addEventListener(
        new FutureEventListener<String>() {
          @Override
          public void onFailure(Throwable e) {
            //System.err.println("GET REPLAY FAILED");
            e.printStackTrace();
          }

          @Override
          public void onSuccess(String resp) {
           // System.err.println("GET REPLAY SUCCESS");
            StringBuffer replayBuffer = new StringBuffer();
            StringBuffer currentCommand = new StringBuffer();

            ArrayList<String> sendList = new ArrayList();
            ArrayList pdfList = new ArrayList();
            int c = 0;
            boolean endOfBuffer = false;
            if (resp.length() <= 0) {
              sender.sendMessage("REPLAY_FINALIZE", context.getRoom().getQualifiedID());
              return;
            }
            StringReader in = new StringReader(resp);
            try {
              while (!endOfBuffer) {
                c = in.read();
                if (c < 0) {
                  endOfBuffer = true;
                } else {
                  currentCommand.append((char) c);
                }

                if (endOfBuffer || ((char) c) == ',') {
                  try {
                    String[] fieldList = currentCommand.toString().split("\\|");
                    String type = fieldList[1];
                    if (type.trim().equalsIgnoreCase("DS")) {
                      try {
                        String stateEncoded = fieldList[3];
                        String stateDecoded =
                            new String(Base64.decodeBase64(stateEncoded.getBytes()));
                        JSONObject state = new JSONObject(stateDecoded);
                        Iterator<String> keys = state.keys();
                        while (keys.hasNext()) {
                          String key = keys.next();
                          String value = (String) ((JSONObject) state.get(key)).get("url");
                          if (!pdfList.contains(value)) {
                            pdfList.add(value);
                          }
                        }
                      } catch (JSONException e) {
                        e.printStackTrace();
                      }
                    }
                    replayBuffer.append(currentCommand);
                    currentCommand.delete(0, currentCommand.length());
                  } catch (Exception e) {
                    e.printStackTrace();
                    currentCommand.delete(0, currentCommand.length());
                  }
                }
                if (endOfBuffer || (replayBuffer.length() > CHUNK_SIZE)) {
                  sendList.add(replayBuffer.toString());
                  replayBuffer.delete(0, replayBuffer.length());
                }
              }
              sender.sendMessage("PRELOAD_PDF", new JSONArray(pdfList).toString());
              for (int i = 0; i < sendList.size(); i++) {
                sender.sendMessage("REPLAY", sendList.get(i));
              }
              sender.sendMessage("REPLAY_FINALIZE", context.getRoom().getQualifiedID());
              sendList.clear();
              pdfList.clear();
            } catch (IOException e) {
              e.printStackTrace();
            } finally {
              in.close();
            }
          }


        });
  }

  public void handlePageChangeEvent(RoomEvent ev) {
    int desiredPage = Integer.parseInt(ev.getMessage().getArg("desiredPage"));
    if (desiredPage > totalPages) {
      totalPages = desiredPage;
      revisionManagerMap.put(desiredPage, new RevisionManager(this, currentPage));
    }

    currentPage = desiredPage;
    addPageChangeToHistory();

    syncStateForPage(desiredPage, context.getRoom().getClients(), false);

    RevisionManager revisionManager = revisionManagerMap.get(desiredPage);
    Set<Client> clientList = ev.getRoom().getClients();
    for (Client client : clientList) {
      initClientTextState(client, desiredPage);
    }
  }

  public void handlePDFUpdateEvent(RoomEvent ev) {
    String pdf = ev.getMessage().getArg("pdf");
    Long currentTime = new Date().getTime();
    Long elapsedMills = currentTime - createdTime;
    String command = elapsedMills + "|DS|-1|" + pdf;
    appendToBuffer(command);
    Set<Client> clientList = context.getRoom().getClients();

    for (Client c : clientList) {
      if (!c.getClientID().trim().equals(ev.getClient().getClientID())) {
        c.sendMessage("PDF_UPDATE", pdf);
      }
    }
  }

  public void handleTextUpdateEvent(RoomEvent ev) {
    String senderID = ev.getClient().getClientID();
    String revisionStr = ev.getMessage().getArg("revision");
    try {
      JSONObject revision = new JSONObject(revisionStr);
      Changeset changeset = Changeset.unpack(revision.getString("changeset"));
      AttribPool pool = null;
      if (revision.has("pool")) {
        pool = AttribPool.fromJsonable(revision.getJSONObject("pool"));
      } else {
        pool = new AttribPool();
      }
      Integer rev = revision.getInt("rev");
      Integer targetPage = revision.getInt("page");
      RevisionManager revisionManager = revisionManagerMap.get(targetPage);
      RevisionRecord head = revisionManager.applyUserChanges(changeset, pool, senderID, rev);

      broadcastChangeset(head.revision, senderID, head.changeset, pool, targetPage);
    } catch (JSONException e) {
      e.printStackTrace();
    }

  }

  public void initClientTextState(Client client, Integer page) {
    try {
      RevisionManager revisionManager = revisionManagerMap.get(page);
      if (!revisionManager.isInitialized()) {
        revisionManager.init("", 0);
        revisionManager.setInitialized(true);
      }

      JSONObject initMsg = new JSONObject();

      initMsg.put("baseText", revisionManager.getBaseText());
      initMsg.put("baseRev", revisionManager.getHeadRevisionNumber());
      initMsg.put("page", page);
      client.sendMessage("INIT_TEXT", initMsg.toString());
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  public void syncRoomClients(Client joinedClient) {
    String myClientId = joinedClient.getClientID();
    StringBuffer otherClientIds = new StringBuffer();
    for (Client client : (Set<Client>) context.getRoom().getClients()) {
      if (!client.getClientID().trim().equalsIgnoreCase(myClientId)) {
        if (otherClientIds.length() > 0) {
          otherClientIds.append("|");
        }
        otherClientIds.append(client.getClientID());
      }
    }
    String clientList = myClientId;
    if (!otherClientIds.toString().isEmpty()) {
      clientList += "|" + otherClientIds.toString();
    }
    for (Client client : (Set<Client>) context.getRoom().getClients()) {
      if (client.getClientID().trim().equalsIgnoreCase(myClientId)) {
        client.sendMessage("SYNC_CLIENT_NEW", clientList);
      } else {
        client.sendMessage("SYNC_CLIENT_UPDATE", clientList);
      }
    }
  }

  public void syncStateForPage(int desiredPage, Set<Client> clientList, Boolean includeChats) {
    StringBuffer syncBuffer = new StringBuffer();
    StringBuffer currentCommand = new StringBuffer();

    int c = 0;
    boolean endOfBuffer = false;
    StringBuffer buffer = getCurrentBuffer();

    if (buffer.length() > 0) {
      StringReader in = new StringReader(buffer.toString());
      try {
        while (!endOfBuffer) {
          c = in.read();
          if (c < 0) {
            endOfBuffer = true;
          } else {
            currentCommand.append((char) c);
          }
          
          if (endOfBuffer || ((char) c) == ',') {
            String[] fieldList = currentCommand.toString().split("\\|");
            try {
              int commandPage = Integer.parseInt(fieldList[2]);
              String commandType = fieldList[1];
              Boolean testCondition = null;
              if (includeChats) {
                testCondition = (commandPage == desiredPage || commandPage < 0 || commandType.trim().equals("CH"));
              } else {
                testCondition = ((commandPage == desiredPage || commandPage < 0) && !commandType.trim().equals("CH"));
              }
              if (testCondition) {
                syncBuffer.append(currentCommand);
                currentCommand.delete(0, currentCommand.length());
              } else {
                currentCommand.delete(0, currentCommand.length());
              }
            } catch (Exception e) {
              e.printStackTrace();
              currentCommand.delete(0, currentCommand.length());
            }
          }

          if (endOfBuffer || (syncBuffer.length() > CHUNK_SIZE)) {
            for (Client client : clientList) {
              try {
                JSONObject syncMsg = new JSONObject();
                syncMsg.put("isEnd", endOfBuffer);
                syncMsg.put("desiredPage", desiredPage);
                syncMsg.put("totalPages", totalPages);
                syncMsg.put("command", syncBuffer.toString());

                client.sendMessage("SYNC_PAGE", syncMsg.toString());
              } catch (JSONException e) {
                e.printStackTrace();
              }
            }
            syncBuffer.delete(0, syncBuffer.length());
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        in.close();
      }
    }
  }
  
  public void syncUserData(RoomEvent ev) {
    JSONObject syncData = new JSONObject(userData);  
    for (Client client : (Set<Client>) context.getRoom().getClients()) {
      //System.err.println("SENDING USERDATA:"+syncData.toString());
      client.sendMessage("SYNC_USER_DATA", syncData.toString());
    }
  }

  private void updateClassStatus() {
    HttpClient httpclient = new DefaultHttpClient();
    try {
        String url = WEB_URL + "/setClassEnded/"+context.getRoom().getQualifiedID();
        HttpGet httpget = new HttpGet(url);
        ResponseHandler<String> responseHandler = new BasicResponseHandler();
        String responseBody = httpclient.execute(httpget, responseHandler);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
        httpclient.getConnectionManager().shutdown();
    }
    
  }
  
  private Boolean signalDownloadArchive() {
    HttpClient httpclient = new DefaultHttpClient();
    try {
        String url = WEB_URL + "/downloadArchive/"+context.getRoom().getQualifiedID();
       // System.err.println("INVOKING downloadarchive URL:"+url);
        HttpGet httpget = new HttpGet(url);
        // Create a response handler
        ResponseHandler<String> responseHandler = new BasicResponseHandler();
        String responseBody = httpclient.execute(httpget, responseHandler);
       // System.err.println("DOWNLOAD ARCHIVE RESPONSE::"+responseBody);
        JSONObject result = new JSONObject(responseBody);
        Boolean status = result.getBoolean("success");
        return status;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    } finally {
        httpclient.getConnectionManager().shutdown();
    }
  }
}

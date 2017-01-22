package com.tutorcast.roommodules;

import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.etherpad.easysync2.AttribPool;
import com.etherpad.easysync2.Changeset;

public class RevisionManager {

  private String baseText;
  private Integer headRev;

  private HashMap<Integer, RevisionRecord> revisionHistory;
  private WhiteboardModule room;
  private Integer pageId;
  private Lock revisionUpdateLock;

  private Boolean initialized;

  public RevisionManager(WhiteboardModule room, Integer pageId) {
    this.room = room;
    this.revisionHistory = new HashMap<Integer, RevisionRecord>();
    this.revisionUpdateLock = new ReentrantLock();
    this.initialized = false;
    this.pageId = pageId;
  }

  public void init(String baseText, int rev) {
    this.headRev = rev;
    this.baseText = baseText;
    this.initialized = true;
  }

  public RevisionRecord applyUserChanges(Changeset c, AttribPool pool, String clientId, Integer rev) {
    try {
      revisionUpdateLock.lock();
      Integer lastClientRev = rev;
      Changeset changeset = c;
      RevisionRecord head = revisionHistory.get(headRev);
      Changeset headrev = null;
      if (head !=null){
        headrev = head.changeset;
      }
      //System.err.println("CLIENT" +clientId+ "REVISION RECEIVED:"+rev+":"+c+"HEAD REVISION:"+headRev+":"+headrev);
      while (lastClientRev <headRev ) {
        ++lastClientRev;
        RevisionRecord currentRecord = revisionHistory.get(lastClientRev);
        Changeset nextChange = currentRecord.changeset;
        changeset = Changeset.follow(nextChange, changeset, false, pool);
        //System.err.println("apply follow on revision::"+lastClientRev+":"+nextChange+", result:"+changeset);
      }
      ++headRev;
      baseText = changeset.applyToText(baseText);
      RevisionRecord r = new RevisionRecord();
      r.changeset = changeset;
      r.revision = headRev;
      r.clientId = clientId;
      r.elapsedTime = (new Date().getTime() - room.createdTime);
      revisionHistory.put(headRev, r);
      return r;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    } finally {
      revisionUpdateLock.unlock();
    }
  }
  

  public Boolean isInitialized() {
    return initialized;
  }

  public void setInitialized(Boolean initialized) {
    this.initialized = initialized;
  }
  
  public String getBaseText() {
    return baseText;
  }

  public void setBaseText(String baseText) {
    this.baseText = baseText;
  }
  
  public Integer getHeadRevisionNumber() {
    return headRev;
  }

  public void setHeadRevisionNumber(Integer baseRev) {
    this.headRev = baseRev;
  }

  public Integer getPageId() {
    return pageId;
  }

  public void setPageId(Integer pageId) {
    this.pageId = pageId;
  }
  
  

}

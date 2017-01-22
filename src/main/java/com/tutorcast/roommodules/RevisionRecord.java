package com.tutorcast.roommodules;

import java.util.ArrayList;

import com.etherpad.easysync2.AttribPool;
import com.etherpad.easysync2.Changeset;

public class RevisionRecord {
  public int revision;
  public Changeset changeset;
  public long elapsedTime;
  public String clientId;
}

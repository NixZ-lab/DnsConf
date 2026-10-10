package com.novibe.dns.next_dns.sync;

import java.util.*;

/** Domain/ID details stay inside authenticated encrypted artifacts. */
public final class ExecutionReport {
    public String profile;
    public String status="Running";
    public String failureType;
    public int added, updated, deleted, conflicts, postRequests, deleteRequests;
    public List<Event> operations=new ArrayList<>();
    public record Event(String action, String id, String name, String content) {}
    public ExecutionReport(String profile,int conflicts) {this.profile=profile;this.conflicts=conflicts;}
}

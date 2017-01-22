Tutorcast Event Server
==========
Tutorcast event server. Written in Java
Handles all client side events relating to document editing, canvas modification (annotations), video start/stop, and chats.
Propagates events to other connected clients, flushs events to distributed cache for recording and  play back
Handles client connects and disconnects.

Uses a modified version of the EasySync protocol (defined in the original Etherpad) for operational transforms. Protocol was modified to support recording.

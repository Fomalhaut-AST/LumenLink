package io.lumenlink.control;

import java.util.concurrent.atomic.AtomicBoolean;

/** Local authorization boundary: input is ignored until the host approves it. */
public final class ControlPermissionGate {
    private final AtomicBoolean granted = new AtomicBoolean(false);

    public boolean grant() { return granted.compareAndSet(false, true); }
    public void revoke() { granted.set(false); }
    public boolean isGranted() { return granted.get(); }
    public boolean allows(RemoteControlEvent event) { return granted.get() && event != null; }
}

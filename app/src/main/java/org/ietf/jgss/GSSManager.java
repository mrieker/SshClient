package org.ietf.jgss;

/**
 * Created by mrieker on 2/9/16.
 */
public class GSSManager {
    public static GSSManager getInstance() throws GSSException {
        throw new GSSException("not supported in jsch Android");
    }
    public GSSName createName(String a, Oid b) { return null; }
    public GSSContext createContext(GSSName a, Oid b, GSSCredential c, int d) { return null; }
}

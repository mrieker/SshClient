package org.ietf.jgss;

/**
 * Created by mrieker on 2/9/16.
 */
public class GSSContext {
    public final static int DEFAULT_LIFETIME = 0;
    public void requestMutualAuth(boolean a) { }
    public void requestConf(boolean a) { }
    public void requestInteg(boolean a) { }
    public void requestCredDeleg(boolean a) { }
    public void requestAnonymity(boolean a) { }
    public boolean isEstablished() { return false; }
    public byte[] initSecContext(byte[] a, int b, int c) throws GSSException {
        throw new GSSException("not supported in jsch Android");
    }
    public void dispose() throws GSSException {
        throw new GSSException("not supported in jsch Android");
    }
    public byte[] getMIC(byte[] a, int b, int c, MessageProp d) throws GSSException {
        throw new GSSException("not supported in jsch Android");
    }
}

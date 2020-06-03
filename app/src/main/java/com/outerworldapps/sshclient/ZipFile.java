/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified Sep 17, 2014 by M.Rieker to use RAInputStream instead of File
 *   plus streamlined seeks.
 */

package com.outerworldapps.sshclient;

import android.support.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * This class provides random read access to a <i>ZIP-archive</i> file.
 * <p>
 * While {@code ZipInputStream} provides stream based read access to a
 * <i>ZIP-archive</i>, this class implements more efficient (file based) access
 * and makes use of the <i>central directory</i> within a <i>ZIP-archive</i>.
 * <p>
 * Use {@code ZipOutputStream} if you want to create an archive.
 * <p>
 * A temporary ZIP file can be marked for automatic deletion upon closing it.
 *
 * @see ZipEntry
 */
public class ZipFile implements ZipConstants {

    private int numEntries;
    private LinkedHashMap<String, ZipEntry> mEntries;
    private long centralDirOffset;
    private RAInputStream mRaf;

    /**
     * Opens a file as <i>ZIP-archive</i>.
     */
    public ZipFile (RAInputStream raf) throws IOException {
        mRaf = raf;
        mRaf.seek (0);
        byte[] b4 = new byte[4];
        mRaf.readFully (b4);
        if ((b4[0] != 0x50) || (b4[1] != 0x4B) || (b4[2] != 0x03) || (b4[3] != 0x04)) {
            throw new ZipException ("magic number doesn't match");
        }
        readCentralDir ();
    }

    /**
     * Closes this ZIP file. This method is idempotent.
     *
     * @throws IOException
     *             if an IOException occurs.
     */
    public void close() throws IOException {
        synchronized (this) {
            RAInputStream raf = mRaf;
            if (raf != null) { // Only close initialized instances
                mRaf = null;
                raf.close();
            }
        }
    }

    private void checkNotClosed() {
        if (mRaf == null) {
            throw new IllegalStateException("Zip File closed.");
        }
    }

    /**
     * Returns an enumeration of the entries. The entries are listed in the
     * order in which they appear in the ZIP archive.
     *
     * @return the enumeration of the entries.
     * @throws IllegalStateException if this ZIP file has been closed.
     */
    public Collection<ZipEntry> entries() throws IOException {
        checkNotClosed();
        makeEntriesTable ();
        return mEntries.values ();
    }

    /**
     * Gets the ZIP entry with the specified name from this {@code ZipFile}.
     *
     * @param entryName
     *            the name of the entry in the ZIP file.
     * @return a {@code ZipEntry} or {@code null} if the entry name does not
     *         exist in the ZIP file.
     * @throws IllegalStateException if this ZIP file has been closed.
     */
    public ZipEntry getEntry(String entryName) throws IOException {
        checkNotClosed();
        if (entryName == null) {
            throw new NullPointerException();
        }

        makeEntriesTable ();
        ZipEntry ze = mEntries.get(entryName);
        if (ze == null) {
            ze = mEntries.get(entryName + "/");
        }
        return ze;
    }

    /**
     * Returns an input stream on the data of the specified {@code ZipEntry}.
     *
     * @param entry
     *            the ZipEntry.
     * @return an input stream of the data contained in the {@code ZipEntry}.
     * @throws IOException
     *             if an {@code IOException} occurs.
     * @throws IllegalStateException if this ZIP file has been closed.
     */
    public InputStream getInputStream(ZipEntry entry) throws IOException {
        /*
         * Make sure this ZipEntry is in this Zip file.  We run it through
         * the name lookup.
         */
        entry = getEntry(entry.getName());
        if (entry == null) {
            return null;
        }

        /*
         * Create a ZipInputStream at the right part of the file.
         */
        synchronized (this) {
            // We don't know the entry data's start position. All we have is the
            // position of the entry's local header. At position 28 we find the
            // length of the extra data. In some cases this length differs from
            // the one coming in the central header.
            RAFStream rafstrm = new RAFStream(mRaf,
                    entry.mLocalHeaderRelOffset + 28);
            int loLELOW = rafstrm.read () & 0xFF;
            int hiLELOW = rafstrm.read () & 0xFF;
            int localExtraLenOrWhatever = (hiLELOW << 8) | loLELOW;
            // Skip the name and this "extra" data or whatever it is:
            long toskip = entry.nameLen + localExtraLenOrWhatever;
            while (toskip > 0) {
                long skipped = rafstrm.skip (toskip);
                if (skipped <= 0) break;
                toskip -= skipped;
            }
            rafstrm.mLength = rafstrm.mOffset + entry.compressedSize;
            if (entry.compressionMethod == ZipEntry.DEFLATED) {
                int bufSize = Math.max(1024, (int)Math.min(entry.getSize(), 65535L));
                return new ZipInflaterInputStream(rafstrm, new Inflater(true), bufSize, entry);
            } else {
                return rafstrm;
            }
        }
    }

    /**
     * Returns the number of {@code ZipEntries} in this {@code ZipFile}.
     *
     * @return the number of entries in this file.
     * @throws IllegalStateException if this ZIP file has been closed.
     */
    public int size() throws IOException {
        checkNotClosed();
        makeEntriesTable ();
        return mEntries.size();
    }

    /**
     * Find the central directory and read the contents.
     *
     * <p>The central directory can be followed by a variable-length comment
     * field, so we have to scan through it backwards.  The comment is at
     * most 64K, plus we have 18 bytes for the end-of-central-dir stuff
     * itself, plus apparently sometimes people throw random junk on the end
     * just for the fun of it.
     *
     * <p>This is all a little wobbly.  If the wrong value ends up in the EOCD
     * area, we're hosed. This appears to be the way that everybody handles
     * it though, so we're in good company if this fails.
     */
    private void readCentralDir() throws IOException {
        /*
         * Scan back, looking for the End Of Central Directory field.  If
         * the archive doesn't have a comment, we'll hit it on the first
         * try.
         *
         * No need to synchronize mRaf here -- we only do this when we
         * first open the Zip file.
         */
        long scanOffset = mRaf.length() - ENDHDR;
        if (scanOffset < 0) {
            throw new ZipException ("too short to be Zip");
        }

        long stopOffset = scanOffset - 65536;
        if (stopOffset < 0) {
            stopOffset = 0;
        }

        eocdBuffer = new byte[(int)(mRaf.length()-stopOffset)];
        mRaf.seek (stopOffset);
        mRaf.readFully (eocdBuffer);

        while (true) {
            eocdOffset = (int)(scanOffset - stopOffset);
            int intLE = (int)readEOCDIntLE ();
            if (intLE == 101010256) {
                break;
            }

            if (-- scanOffset < stopOffset) {
                throw new ZipException("EOCD not found; not a Zip archive?");
            }
        }

        /*
         * Found it, read the EOCD.
         */
        int diskNumber = readEOCDShortLE ();
        int diskWithCentralDir = readEOCDShortLE ();
        numEntries = readEOCDShortLE ();
        int totalNumEntries = readEOCDShortLE ();
        /*centralDirSize =*/ readEOCDIntLE ();
        centralDirOffset = readEOCDIntLE ();
        /*commentLen =*/ readEOCDShortLE ();

        if (numEntries != totalNumEntries ||
            diskNumber != 0 ||
            diskWithCentralDir != 0) {
            throw new ZipException("spanned archives not supported");
        }

        eocdBuffer = null;
    }

    private byte[] eocdBuffer;
    private int eocdOffset;

    private int readEOCDShortLE ()
    {
        int b0 = eocdBuffer[eocdOffset++] & 0xFF;
        int b1 = eocdBuffer[eocdOffset++] & 0xFF;
        return (b1 << 8) | b0;
    }

    private long readEOCDIntLE ()
    {
        int  b0 = eocdBuffer[eocdOffset++] & 0xFF;
        int  b1 = eocdBuffer[eocdOffset++] & 0xFF;
        int  b2 = eocdBuffer[eocdOffset++] & 0xFF;
        long b3 = eocdBuffer[eocdOffset++] & 0xFF;
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }

    /**
     * Read all entries from the central directory.
     */
    private void makeEntriesTable ()
            throws IOException
    {
        if (mEntries == null) {

            /*
             * Seek to the first CDE and read all entries.
             *
             * For performance we want to use buffered I/O when reading the
             * file.  We wrap a buffered stream around the random-access file
             * object.  If we just read from the RandomAccessFile we'll be
             * doing a read() system call every time.
             */
            mEntries = new LinkedHashMap<> ();
            RAFStream rafs = new RAFStream (mRaf, centralDirOffset);
            BufferedInputStream bin = new BufferedInputStream(rafs, 4096);
            for (int i = 0; i < numEntries; i++) {
                ZipEntry newEntry = new ZipEntry (bin);
                mEntries.put(newEntry.getName(), newEntry);
            }
        }
    }

    /**
     * Wrap a stream around a RandomAccessFile.  The RandomAccessFile is shared
     * among all streams returned by getInputStream(), so we have to synchronize
     * access to it.  (We can optimize this by adding buffering here to reduce
     * collisions.)
     *
     * <p>We could support mark/reset, but we don't currently need them.
     */
    static class RAFStream extends InputStream {

        private final RAInputStream mSharedRaf;
        private long mOffset;
        private long mLength;

        public RAFStream(RAInputStream raf, long pos) throws IOException {
            mSharedRaf = raf;
            mOffset = pos;
            mLength = raf.length();
        }

        @Override
        public int available()
        {
            return (mOffset < mLength ? 1 : 0);
        }

        @Override
        public int read() throws IOException {
            byte[] singleByteBuf = new byte[1];
            if (read(singleByteBuf, 0, 1) == 1) {
                return singleByteBuf[0] & 0XFF;
            } else {
                return -1;
            }
        }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            synchronized (mSharedRaf) {
                mSharedRaf.seek(mOffset);
                if (len > mLength - mOffset) {
                    len = (int) (mLength - mOffset);
                }
                int count = mSharedRaf.read(b, off, len);
                if (count > 0) {
                    mOffset += count;
                    return count;
                } else {
                    return -1;
                }
            }
        }

        @Override
        public long skip(long n)
        {
            if (n > mLength - mOffset) {
                n = mLength - mOffset;
            }
            mOffset += n;
            return n;
        }
    }
    
    static class ZipInflaterInputStream extends InflaterInputStream {

        ZipEntry entry;
        long bytesRead = 0;

        public ZipInflaterInputStream(InputStream is, Inflater inf, int bsize, ZipEntry entry) {
            super(is, inf, bsize);
            this.entry = entry;
        }

        @Override
        public int read(byte[] buffer, int off, int nbytes) throws IOException {
            int i = super.read(buffer, off, nbytes);
            if (i != -1) {
                bytesRead += i;
            }
            return i;
        }

        @Override
        public int available() throws IOException {
            return super.available() == 0 ? 0 : (int) (entry.getSize() - bytesRead);
        }
    }
}

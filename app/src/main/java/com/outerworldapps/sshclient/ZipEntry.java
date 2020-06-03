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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.zip.ZipException;

/**
 * An instance of {@code ZipEntry} represents an entry within a <i>ZIP-archive</i>.
 * An entry has attributes such as name (= path) or the size of its data. While
 * an entry identifies data stored in an archive, it does not hold the data
 * itself. For example when reading a <i>ZIP-file</i> you will first retrieve
 * all its entries in a collection and then read the data for a specific entry
 * through an input stream.
 *
 * @see ZipFile
 */
@SuppressWarnings("unused")
public class ZipEntry implements ZipConstants, Cloneable {
    String name, comment;

    long compressedSize, crc, size;

    int compressionMethod, time, modDate;

    byte[] extra;

    int nameLen;
    long mLocalHeaderRelOffset;

    /**
     * Zip entry state: Deflated.
     */
    public static final int DEFLATED = 8;

    /**
     * Zip entry state: Stored.
     */
    public static final int STORED = 0;

    /**
     * Gets the comment for this {@code ZipEntry}.
     *
     * @return the comment for this {@code ZipEntry}, or {@code null} if there
     *         is no comment. If we're reading an archive with
     *         {@code ZipInputStream} the comment is not available.
     */
    public String getComment() {
        return comment;
    }

    /**
     * Gets the extra information for this {@code ZipEntry}.
     *
     * @return a byte array containing the extra information, or {@code null} if
     *         there is none.
     */
    public byte[] getExtra() {
        return extra;
    }

    /**
     * Gets the compression method for this {@code ZipEntry}.
     *
     * @return the compression method, either {@code DEFLATED}, {@code STORED}
     *         or -1 if the compression method has not been set.
     */
    public int getMethod() {
        return compressionMethod;
    }

    /**
     * Gets the name of this {@code ZipEntry}.
     *
     * @return the entry name.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the uncompressed size of this {@code ZipEntry}.
     *
     * @return the uncompressed size, or {@code -1} if the size has not been
     *         set.
     */
    public long getSize() {
        return size;
    }

    /**
     * Gets the last modification time of this {@code ZipEntry}.
     *
     * @return the last modification time as the number of milliseconds since
     *         Jan. 1, 1970.
     */
    public long getTime() {
        if (time != -1) {
            GregorianCalendar cal = new GregorianCalendar();
            cal.set(Calendar.MILLISECOND, 0);
            cal.set(1980 + ((modDate >> 9) & 0x7f), ((modDate >> 5) & 0xf) - 1,
                    modDate & 0x1f, (time >> 11) & 0x1f, (time >> 5) & 0x3f,
                    (time & 0x1f) << 1);
            return cal.getTime().getTime();
        }
        return -1;
    }

    /**
     * Determine whether or not this {@code ZipEntry} is a directory.
     *
     * @return {@code true} when this {@code ZipEntry} is a directory, {@code
     *         false} otherwise.
     */
    public boolean isDirectory() {
        return name.charAt(name.length() - 1) == '/';
    }

    /**
     * Sets the comment for this {@code ZipEntry}.
     *
     * @param string
     *            the comment for this entry.
     */
    public void setComment(String string) {
        if (string == null || string.length() <= 0xFFFF) {
            comment = string;
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Sets the extra information for this {@code ZipEntry}.
     *
     * @param data
     *            a byte array containing the extra information.
     * @throws IllegalArgumentException
     *             when the length of data is greater than 0xFFFF bytes.
     */
    public void setExtra(byte[] data) {
        if (data == null || data.length <= 0xFFFF) {
            extra = data;
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Sets the compression method for this {@code ZipEntry}.
     *
     * @param value
     *            the compression method, either {@code DEFLATED} or {@code
     *            STORED}.
     * @throws IllegalArgumentException
     *             when value is not {@code DEFLATED} or {@code STORED}.
     */
    public void setMethod(int value) {
        if (value != STORED && value != DEFLATED) {
            throw new IllegalArgumentException();
        }
        compressionMethod = value;
    }

    /**
     * Sets the uncompressed size of this {@code ZipEntry}.
     *
     * @param value
     *            the uncompressed size for this entry.
     * @throws IllegalArgumentException
     *             if {@code value} < 0 or {@code value} > 0xFFFFFFFFL.
     */
    public void setSize(long value) {
        if (value >= 0 && value <= 0xFFFFFFFFL) {
            size = value;
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Sets the modification time of this {@code ZipEntry}.
     *
     * @param value
     *            the modification time as the number of milliseconds since Jan.
     *            1, 1970.
     */
    public void setTime(long value) {
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTime(new Date(value));
        int year = cal.get(Calendar.YEAR);
        if (year < 1980) {
            modDate = 0x21;
            time = 0;
        } else {
            modDate = cal.get(Calendar.DATE);
            modDate = (cal.get(Calendar.MONTH) + 1 << 5) | modDate;
            modDate = ((cal.get(Calendar.YEAR) - 1980) << 9) | modDate;
            time = cal.get(Calendar.SECOND) >> 1;
            time = (cal.get(Calendar.MINUTE) << 5) | time;
            time = (cal.get(Calendar.HOUR_OF_DAY) << 11) | time;
        }
    }

    /**
     * Returns the string representation of this {@code ZipEntry}.
     *
     * @return the string representation of this {@code ZipEntry}.
     */
    @Override
    public String toString() {
        return name;
    }

    /**
     * Returns the hash code for this {@code ZipEntry}.
     *
     * @return the hash code of the entry.
     */
    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /*
     * Internal constructor.  Creates a new ZipEntry by reading the
     * Central Directory Entry from "in", which must be positioned at
     * the CDE signature.
     *
     * On exit, "in" will be positioned at the start of the next entry.
     */
    ZipEntry (InputStream in) throws IOException {

        /*
         * We're seeing performance issues when we call readShortLE and
         * readIntLE, so we're going to read the entire header at once
         * and then parse the results out without using any function calls.
         * Uglier, but should be much faster.
         *
         * Note that some lines look a bit different, because the corresponding
         * fields or locals are long and so we need to do & 0xffffffffl to avoid
         * problems induced by sign extension.
         */

        byte[] hdrBuf = new byte[CENHDR];
        myReadFully(in, hdrBuf);

        long sig = (hdrBuf[0] & 0xff) | ((hdrBuf[1] & 0xff) << 8) |
            ((hdrBuf[2] & 0xff) << 16) | ((hdrBuf[3] << 24) & 0xffffffffL);
        if (sig != CENSIG) {
             throw new ZipException ("Central Directory Entry not found");
        }

        compressionMethod = (hdrBuf[10] & 0xff) | ((hdrBuf[11] & 0xff) << 8);
        time = (hdrBuf[12] & 0xff) | ((hdrBuf[13] & 0xff) << 8);
        modDate = (hdrBuf[14] & 0xff) | ((hdrBuf[15] & 0xff) << 8);
        crc = (hdrBuf[16] & 0xff) | ((hdrBuf[17] & 0xff) << 8)
                | ((hdrBuf[18] & 0xff) << 16)
                | ((hdrBuf[19] << 24) & 0xffffffffL);
        compressedSize = (hdrBuf[20] & 0xff) | ((hdrBuf[21] & 0xff) << 8)
                | ((hdrBuf[22] & 0xff) << 16)
                | ((hdrBuf[23] << 24) & 0xffffffffL);
        size = (hdrBuf[24] & 0xff) | ((hdrBuf[25] & 0xff) << 8)
                | ((hdrBuf[26] & 0xff) << 16)
                | ((hdrBuf[27] << 24) & 0xffffffffL);
        nameLen = (hdrBuf[28] & 0xff) | ((hdrBuf[29] & 0xff) << 8);
        int extraLen = (hdrBuf[30] & 0xff) | ((hdrBuf[31] & 0xff) << 8);
        int commentLen = (hdrBuf[32] & 0xff) | ((hdrBuf[33] & 0xff) << 8);
        mLocalHeaderRelOffset = (hdrBuf[42] & 0xff) | ((hdrBuf[43] & 0xff) << 8)
                | ((hdrBuf[44] & 0xff) << 16)
                | ((hdrBuf[45] << 24) & 0xffffffffL);

        byte[] nameBytes = new byte[nameLen];
        myReadFully(in, nameBytes);

        byte[] commentBytes = null;
        if (commentLen > 0) {
            commentBytes = new byte[commentLen];
            myReadFully(in, commentBytes);
        }

        if (extraLen > 0) {
            extra = new byte[extraLen];
            myReadFully(in, extra);
        }

        try {
            /*
             * The actual character set is "IBM Code Page 437".  As of
             * Sep 2006, the Zip spec (APPNOTE.TXT) supports UTF-8.  When
             * bit 11 of the GP flags field is set, the file name and
             * comment fields are UTF-8.
             *
             * TODO: add correct UTF-8 support.
             */
            name = new String(nameBytes, "ISO-8859-1");
            if (commentBytes != null) {
                comment = new String(commentBytes, "ISO-8859-1");
            } else {
                comment = null;
            }
        } catch (UnsupportedEncodingException uee) {
            throw new InternalError(uee.getMessage());
        }
    }

    private static void myReadFully (InputStream in, byte[] b) throws IOException {
        int len = b.length;
        int off = 0;

        while (len > 0) {
            int count = in.read(b, off, len);
            if (count <= 0) {
                throw new EOFException();
            }
            off += count;
            len -= count;
        }
    }
}

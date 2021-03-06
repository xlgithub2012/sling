/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.event.impl.jobs;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Dictionary;
import java.util.Hashtable;

import org.apache.sling.event.impl.EnvironmentComponent;
import org.apache.sling.event.impl.support.Environment;
import org.apache.sling.event.jobs.JobUtil;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;

public abstract class Utility {

    /** Allowed characters for a node name */
    private static final BitSet ALLOWED_CHARS;

    /** Replacement characters for unallowed characters in a node name */
    private static final char REPLACEMENT_CHAR = '_';

    // Prepare the ALLOWED_CHARS bitset with bits indicating the unicode
    // character index of allowed characters. We deliberately only support
    // a subset of the actually allowed set of characters for nodes ...
    static {
        final String allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz0123456789_,.-+#!?$%&()=";
        final BitSet allowedSet = new BitSet();
        for (int i = 0; i < allowed.length(); i++) {
            allowedSet.set(allowed.charAt(i));
        }
        ALLOWED_CHARS = allowedSet;
    }

    /**
     * Filter the node name for not allowed characters and replace them.
     * @param nodeName The suggested node name.
     * @return The filtered node name.
     */
    public static String filter(final String nodeName) {
        final StringBuilder sb  = new StringBuilder(nodeName.length());
        char lastAdded = 0;

        for(int i=0; i < nodeName.length(); i++) {
            final char c = nodeName.charAt(i);
            char toAdd = c;

            if (!ALLOWED_CHARS.get(c)) {
                if (lastAdded == REPLACEMENT_CHAR) {
                    // do not add several _ in a row
                    continue;
                }
                toAdd = REPLACEMENT_CHAR;

            } else if(i == 0 && Character.isDigit(c)) {
                sb.append(REPLACEMENT_CHAR);
            }

            sb.append(toAdd);
            lastAdded = toAdd;
        }

        if (sb.length()==0) {
            sb.append(REPLACEMENT_CHAR);
        }

        return sb.toString();
    }

    /**
     * used for the md5
     */
    private static final char[] HEX_TABLE = "0123456789abcdef".toCharArray();

    /**
     * Calculate an MD5 hash of the string given using 'utf-8' encoding.
     *
     * @param data the data to encode
     * @return a hex encoded string of the md5 digested input
     */
    public static String md5(String data) {
        try {
            return digest("MD5", data.getBytes("utf-8"));
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("MD5 digest not available???");
        } catch (UnsupportedEncodingException e) {
            throw new InternalError("UTF8 digest not available???");
        }
    }

    /**
     * Digest the plain string using the given algorithm.
     *
     * @param algorithm The alogrithm for the digest. This algorithm must be
     *                  supported by the MessageDigest class.
     * @param data      the data to digest with the given algorithm
     * @return The digested plain text String represented as Hex digits.
     * @throws java.security.NoSuchAlgorithmException if the desired algorithm is not supported by
     *                                  the MessageDigest class.
     */
    private static String digest(String algorithm, byte[] data)
    throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] digest = md.digest(data);
        StringBuilder res = new StringBuilder(digest.length * 2);
        for (int i = 0; i < digest.length; i++) {
            byte b = digest[i];
            res.append(HEX_TABLE[(b >> 4) & 15]);
            res.append(HEX_TABLE[b & 15]);
        }
        return res.toString();
    }

    /** Counter for jobs without an id.  We don't need to sync the access. */
    private static long JOB_COUNTER = 0;

    /**
     * Create a unique node path (folder and name) for the job.
     */
    public static String getUniquePath(final String jobTopic, final String jobId) {
        final String convTopic = jobTopic.replace('/', '.');
        if ( jobId != null ) {
            final StringBuilder sb = new StringBuilder("identified/");
            sb.append(convTopic);
            sb.append('/');
            // we create an md from the job id - we use the first 6 bytes to
            // create sub directories
            final String md5 = md5(jobId);
            sb.append(md5.charAt(0));
            sb.append(md5.charAt(1));
            sb.append(md5.charAt(2));
            sb.append('/');
            sb.append(md5.charAt(3));
            sb.append(md5.charAt(4));
            sb.append(md5.charAt(5));
            sb.append('/');
            sb.append(filter(jobId));
            return sb.toString();
        }
        final Calendar now = Calendar.getInstance();
        // create a time based path together with the Sling ID
        final StringBuilder sb = getAnonPath(now);
        sb.append('/');
        sb.append(convTopic);
        sb.append('_');
        sb.append(JOB_COUNTER);
        JOB_COUNTER++;
        return sb.toString();
    }

    public static StringBuilder getAnonPath(final Calendar now) {
        final StringBuilder sb = new StringBuilder("anon/");
        // create a time based path together with the Sling ID
        sb.append(Environment.APPLICATION_ID);
        sb.append('/');
        sb.append(now.get(Calendar.YEAR));
        sb.append('/');
        sb.append(now.get(Calendar.MONTH) + 1);
        sb.append('/');
        sb.append(now.get(Calendar.DAY_OF_MONTH));
        sb.append('/');
        sb.append(now.get(Calendar.HOUR_OF_DAY));
        sb.append('/');
        sb.append(now.get(Calendar.MINUTE));
        return sb;
    }

    /** Event property containing the time for job start and job finished events. */
    public static final String PROPERTY_TIME = "time";

    /**
     * Helper method for sending the notification events.
     */
    public static void sendNotification(final EnvironmentComponent environment,
            final String topic,
            final Event job,
            final Long time) {
        final EventAdmin localEA = environment.getEventAdmin();
        final Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(JobUtil.PROPERTY_NOTIFICATION_JOB, job);
        props.put(EventConstants.TIMESTAMP, System.currentTimeMillis());
        if ( time != null ) {
            props.put(PROPERTY_TIME, time);
        }
        localEA.postEvent(new Event(topic, props));
    }
}

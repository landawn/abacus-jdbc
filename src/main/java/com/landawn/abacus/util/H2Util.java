/*
 * Copyright (C) 2016 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.util;

import java.sql.SQLException;

import org.h2.tools.Server;

// TODO: Auto-generated Javadoc
/**
 *
 * @author Haiyang Li
 * @since 0.8
 */
public final class H2Util {

    /**
     * Instantiates a new h 2 util.
     */
    private H2Util() {
        // Singleton.
    }

    /**
     * Start tcp server.
     *
     * @throws SQLException the SQL exception
     */
    public static void startTcpServer() throws SQLException {
        startTcpServer(9092);
    }

    /**
     * Start tcp server.
     *
     * @param tcpPort
     * @throws SQLException the SQL exception
     */
    public static void startTcpServer(int tcpPort) throws SQLException {
        Server.createTcpServer("-tcpPort", String.valueOf(tcpPort)).start();
    }

    /**
     * Stop tcp server.
     *
     * @throws SQLException the SQL exception
     */
    public static void stopTcpServer() throws SQLException {
        stopTcpServer(9092);
    }

    /**
     * Stop tcp server.
     *
     * @param portNum
     * @throws SQLException the SQL exception
     */
    public static void stopTcpServer(int portNum) throws SQLException {
        Server.shutdownTcpServer("tcp://localhost:" + portNum, "", true, true);
    }

    /**
     *
     * @param args
     * @throws SQLException the SQL exception
     * @see <a href="http://www.h2database.com/javadoc/org/h2/tools/Server.html">Server.html</a>
     */
    @SafeVarargs
    public static void execute(String... args) throws SQLException {
        Server.main(args);
    }
}

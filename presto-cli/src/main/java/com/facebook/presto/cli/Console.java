/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.cli;

import com.facebook.presto.cli.ClientOptions.OutputFormat;
import com.facebook.presto.client.ClientSession;
import com.facebook.presto.sql.parser.ParsingException;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.parser.StatementSplitter;
import com.facebook.presto.sql.tree.UseCollection;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import io.airlift.command.Command;
import io.airlift.command.HelpOption;
import io.airlift.log.Logging;
import io.airlift.log.LoggingConfiguration;
import jline.console.history.FileHistory;
import jline.console.history.MemoryHistory;
import org.fusesource.jansi.AnsiConsole;

import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import static com.facebook.presto.cli.Help.getHelpText;
import static com.facebook.presto.sql.parser.StatementSplitter.Statement;
import static com.facebook.presto.sql.parser.StatementSplitter.squeezeStatement;
import static com.google.common.io.ByteStreams.nullOutputStream;
import static io.airlift.log.Logging.Level;
import static java.lang.String.format;
import static jline.internal.Configuration.getUserHome;

@Command(name = "presto", description = "Presto interactive console")
public class Console
        implements Runnable
{
    private static final String PROMPT_NAME = "DQP-Terminal-BETA";
    private String filter_string = null;

    @Inject
    public HelpOption helpOption;

    @Inject
    public ClientOptions clientOptions = new ClientOptions();

    @Override
    public void run()
    {
        ClientSession session = clientOptions.toClientSession();
        boolean hasQuery = !Strings.isNullOrEmpty(clientOptions.execute);
        boolean isFromFile = !Strings.isNullOrEmpty(clientOptions.file);

        if (!hasQuery || !isFromFile) {
            AnsiConsole.systemInstall();
        }

        initializeLogging(session.isDebug());

        String query = clientOptions.execute;
        if (isFromFile) {
            if (hasQuery) {
                throw new RuntimeException("both --execute and --file specified");
            }
            try {
                query = Files.toString(new File(clientOptions.file), Charsets.UTF_8);
                hasQuery = true;
            }
            catch (IOException e) {
                throw new RuntimeException(format("Error reading from file %s: %s", clientOptions.file, e.getMessage()));
            }
        }

        try (QueryRunner queryRunner = QueryRunner.create(session)) {
            if (hasQuery) {
                executeCommand(queryRunner, query, clientOptions.outputFormat, filter_string);
            }
            else {
                runConsole(queryRunner, session);
            }
        }
    }

    @SuppressWarnings("fallthrough")
    private void runConsole(QueryRunner queryRunner, ClientSession session)
    {
        try (TableNameCompleter tableNameCompleter = new TableNameCompleter(clientOptions.toClientSession(), queryRunner);
                LineReader reader = new LineReader(getHistory(), tableNameCompleter)) {
            tableNameCompleter.populateCache(session.getSchema());
            StringBuilder buffer = new StringBuilder();
            while (true) {
                // read a line of input from user
            	String prompt = PROMPT_NAME ; // String prompt = PROMPT_NAME + ":" + session.getSchema();
                if (buffer.length() > 0) {
                    prompt = Strings.repeat(" ", prompt.length() - 1) + "-";
                }
                String line = reader.readLine(prompt + "> ");

                // add buffer to history and clear on user interrupt
                if (reader.interrupted()) {
                    String partial = squeezeStatement(buffer.toString());
                    if (!partial.isEmpty()) {
                        reader.getHistory().add(partial);
                    }
                    buffer = new StringBuilder();
                    continue;
                }

                // exit on EOF
                if (line == null) {
                    return;
                }

                // check for special commands if this is the first line
                if (buffer.length() == 0) {
                    String command = line.trim();
                    if (filter_string !=null) filter_string = null;
                    if(command.contains(Constants.LOWERCASE_FILTER_WITH) || command.contains(Constants.UPPERCASE_FILTER_WITH)) {
                    	String temp1 = command.toLowerCase().split(Constants.LOWERCASE_FILTER_WITH)[0];
                    	filter_string = command.toLowerCase().split(Constants.LOWERCASE_FILTER_WITH)[1];
                    	command = temp1.split(Constants.SEMI_COLON)[0];
                    	command += Constants.SEMI_COLON;
                    	command.trim();
                    	line = command;
                    }
                    if (command.endsWith(Constants.SEMI_COLON)) {
                        command = command.substring(0, command.length() - 1).trim();
                    }
                    switch (command.toLowerCase()) {
                        case "exit":
                        case "quit":
                            return;
                        case "help":
                            System.out.println();
                            System.out.println(getHelpText());
                            continue;
                    }
                    if(!(command.toLowerCase().contains("select") || command.toLowerCase().contains("show") || command.toLowerCase().contains("explain") | command.toLowerCase().contains("describe"))) {
                    	System.out.println("Only SELECT, SHOW, EXPLAIN, DESCRIBE functions supported over Command Line Client. For other functions, please use the API.");
                    	line = null;
                    	continue;
                    }
                }

                // not a command, add line to buffer
                buffer.append(line).append("\n");

                // execute any complete statements
                String sql = buffer.toString();
                StatementSplitter splitter = new StatementSplitter(sql, ImmutableSet.of(";", "\\G"));
                for (Statement split : splitter.getCompleteStatements()) {
                    Optional<Object> statement = getParsedStatement(split.statement());
                    if (statement.isPresent() && isSessionParameterChange(statement.get())) {
                        session = processSessionParameterChange(statement.get(), session);
                        queryRunner = QueryRunner.create(session);
                    }
                    else {
                        OutputFormat outputFormat = OutputFormat.ALIGNED;
                        if (split.terminator().equals("\\G")) {
                            outputFormat = OutputFormat.VERTICAL;
                        }

                        process(queryRunner, split.statement(), outputFormat, true, filter_string);
                    }
                    reader.getHistory().add(squeezeStatement(split.statement()) + split.terminator());
                }

                // replace buffer with trailing partial statement
                buffer = new StringBuilder();
                String partial = splitter.getPartialStatement();
                if (!partial.isEmpty()) {
                    buffer.append(partial).append('\n');
                }
            }
        }
        catch (IOException e) {
            System.err.println("Readline error: " + e.getMessage());
        }
    }

    private Optional<Object> getParsedStatement(String statement)
    {
        try {
            return Optional.of((Object) SqlParser.createStatement(statement));
        }
        catch (ParsingException e) {
            return Optional.absent();
        }
    }

    static ClientSession processSessionParameterChange(Object parsedStatement, ClientSession session)
    {
        if (parsedStatement instanceof UseCollection) {
            UseCollection useCollection = (UseCollection) parsedStatement;
            if (useCollection.getType() == UseCollection.CollectionType.CATALOG) {
                return ClientSession.withCatalog(session, useCollection.getCollection());
            }
            else if (useCollection.getType() == UseCollection.CollectionType.SCHEMA) {
                return ClientSession.withSchema(session, useCollection.getCollection());
            }
        }
        return session;
    }

    static boolean isSessionParameterChange(Object statement)
    {
        return statement instanceof UseCollection;
    }

    private static void executeCommand(QueryRunner queryRunner, String query, OutputFormat outputFormat, String filter_with)
    {
        StatementSplitter splitter = new StatementSplitter(query + ";");
        for (Statement split : splitter.getCompleteStatements()) {
            process(queryRunner, split.statement(), outputFormat, false, filter_with);
        }
    }

    private static void process(QueryRunner queryRunner, String sql, OutputFormat outputFormat, boolean interactive, String filter_with)
    {
        try (Query query = queryRunner.startQuery(sql)) {
            query.renderOutput(System.out, outputFormat, interactive, filter_with);
        }
        catch (RuntimeException e) {
            System.out.println("Error running command: " + e.getMessage());
            if (queryRunner.getSession().isDebug()) {
                e.printStackTrace();
            }
        }
    }

    private static MemoryHistory getHistory()
    {
        MemoryHistory history;
        File historyFile = new File(getUserHome(), ".presto_history");
        try {
            history = new FileHistory(historyFile);
        }
        catch (IOException e) {
            System.err.printf("WARNING: Failed to load history file (%s): %s. " +
                    "History will not be available during this session.%n",
                    historyFile, e.getMessage());
            history = new MemoryHistory();
        }
        history.setAutoTrim(true);
        return history;
    }

    private static void initializeLogging(boolean debug)
    {
        // unhook out and err while initializing logging or logger will print to them
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            if (debug) {
                Logging logging = Logging.initialize();
                logging.configure(new LoggingConfiguration());
                logging.setLevel("com.facebook.presto", Level.DEBUG);
            }
            else {
                System.setOut(nullPrintStream());
                System.setErr(nullPrintStream());

                Logging logging = Logging.initialize();
                logging.configure(new LoggingConfiguration());
                logging.disableConsole();
            }
        }
        catch (IOException e) {
            throw Throwables.propagate(e);
        }
        finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static PrintStream nullPrintStream()
    {
        return new PrintStream(nullOutputStream());
    }
}

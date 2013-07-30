package net.ripe.db.whois.wsearch;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import net.ripe.db.whois.common.IntegrationTest;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.junit.*;
import org.junit.experimental.categories.Category;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;

import java.io.*;
import java.net.URLEncoder;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@Category(IntegrationTest.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(locations = {"classpath:applicationContext-wsearch-test.xml"})
public class WSearchTestIntegration extends AbstractJUnit4SpringContextTests {
    @Autowired
    private WSearchJettyBootstrap wSearchJettyBootstrap;
    @Autowired
    private WSearchJettyConfig wSearchJettyConfig;

    @Autowired
    private LogFileIndex logFileIndex;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormat.forPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormat.forPattern("HHmmss");
    private static final String INPUT_FILE_NAME = "001.msg-in.txt.gz";
    private static final Splitter PATH_SPLITTER = Splitter.on('/');

    private Client client;
    private File logFile;


    @Value("${dir.update.audit.log}")
    private String logDir;

    @Value("${api.key}")
    private String apiKey;

    @BeforeClass
    public static void setupClass() {
        System.setProperty("dir.wsearch.index", "var1");
    }

    @Before
    public void setup() {
        wSearchJettyBootstrap.start();
        client = Client.create(new DefaultClientConfig());
    }

    @After
    public void removeLogfile() {
        if (logFile != null) {
            logFile.delete();
        }
        wSearchJettyBootstrap.stop(true);
    }

    @Test
    public void single_term() throws Exception {
        createLogFile("the quick brown fox");

        assertThat(getUpdates("quick"), containsString("the quick brown fox"));
    }

    @Test
    public void realisticPathTest() throws Exception {
        createLogFileNew("the quick brown fox");
        logFileIndex.update();

        assertThat(getUpdates("quick"), containsString("the quick brown fox"));
    }

    @Ignore("TODO: [ES] fix")
    @Test
    public void single_term_inetnum_with_prefix_length() throws Exception {
        createLogFile("inetnum: 10.0.0.0/24");

        assertThat(getUpdates("10.0.0.0\\/24"), containsString("inetnum: 10.0.0.0/24"));
    }

    @Test
    public void multiple_inetnum_terms() throws Exception {
        createLogFile("inetnum: 192.0.0.0 - 193.0.0.0");

        final String response = getUpdates("192.0.0.0 - 193.0.0.0");

        assertThat(response, containsString("192.0.0.0 - 193.0.0.0"));
    }

    //@Ignore("TODO: [ES] fix tokenizer, query string shouldn't match")
    @Test
    public void single_inet6num_term() throws Exception {
        createLogFile("inet6num: 2001:a08:cafe::/48");

        assertThat(getUpdates("2001:cafe"), not(containsString("2001:a08:cafe::/48")));
    }

    @Ignore("TODO: [ES] fix")
    @Test
    public void curly_brace_in_search_term() throws Exception {
        createLogFile("mnt-routes: ROUTES-MNT {2001::/48}");

        final String response = getUpdates("{2001::/48}");

        assertThat(response, containsString("ROUTES-MNT {2001::/48}"));
    }

    @Test
    public void search_multiple_terms_in_failed_update() throws Exception {
        createLogFile(
            "SUMMARY OF UPDATE:\n"+
            "\n"+
            "Number of objects found:                   1\n"+
            "Number of objects processed successfully:  0\n"+
            " Create:         0\n"+
            " Modify:         0\n"+
            " Delete:         0\n"+
            " No Operation:   0\n"+
            "Number of objects processed with errors:   1\n"+
            " Create:         1\n"+
            " Modify:         0\n"+
            " Delete:         0\n"+
            "\n"+
            "DETAILED EXPLANATION:\n"+
            "\n"+
            "\n"+
            "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n"+
            "The following object(s) were found to have ERRORS:\n"+
            "\n"+
            "---\n"+
            "Create FAILED: [person] FP1-TEST   First Person\n"+
            "\n"+
            "person:         First Person\n"+
            "address:        St James Street\n"+
            "address:        Burnley\n"+
            "address:        UK\n"+
            "phone:          +44 282 420469\n"+
            "nic-hdl:        FP1-TEST\n"+
            "mnt-by:         OWNER-MNT\n"+
            "changed:        user@ripe.net\n"+
            "source:         TEST\n"+
            "\n"+
            "***Error:   Authorisation for [person] FP1-TEST failed\n"+
            "           using \"mnt-by:\"\n"+
            "           not authenticated by: OWNER-MNT\n"+
            "\n\n\n"+
            "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n");

        final String response = getUpdates("FAILED: mnt-by: OWNER-MNT");

        assertThat(response, containsString("First Person"));
    }

    @Test
    public void search_date_range() throws IOException {
        createLogFile("mntner: TEST-MNT");

        String response = client
                .resource(String.format(
                        "http://localhost:%s/api/logs?search=%s&fromdate=%s&todate=%s&apiKey=%s",
                        wSearchJettyConfig.getPort(),
                        URLEncoder.encode("TEST-MNT", "ISO-8859-1"),
                        getDate(),
                        getDate(),
                        apiKey))
                .get(String.class);

        assertThat(response, containsString("TEST-MNT"));
    }

    @Test
    public void get_update_logs_for_id() throws Exception {
        createLogFile("mntner: TEST-MNT");

        final String response = getCurrentUpdateLogsForId(URLEncoder.encode(getLogDirFullPathName(), "ISO-8859-1"));

        assertThat(response, containsString("TEST-MNT"));
    }

    @Test
    public void get_update_logs_for_id_and_date() throws Exception {
        createLogFile("mntner: TEST-MNT");

        final String response = getCurrentUpdateLogsForIdAndDate(getLogDirName(), getDate());

        assertThat(response, containsString("TEST-MNT"));
    }

    @Test
    public void get_update_ids_for_name_and_date() throws Exception {
        createLogFile("mntner: TEST-MNT");

        final String response = getUpdateIds("TEST-MNT", getDate());

        assertThat(response, containsString("\"host\":"));
        assertThat(response, containsString("\"id\":"));
    }

    @Test
    public void get_current_update_logs_for_name_and_date() throws Exception {
        createLogFile("mntner: TEST-MNT");

        final String date = getDate();
        final String response = getUpdates("TEST-MNT", date);

        assertThat(response, containsString("mntner: TEST-MNT"));
    }

    @Test
    public void search_from_inetnum() throws IOException {
        createLogFile("REQUEST FROM:193.0.1.204\nPARAMS:");

        final String response = getUpdates("193.0.1.204", getDate());

        assertThat(response, containsString("REQUEST FROM:193.0.1.204"));
    }

    @Ignore("TODO")
    @Test
    public void search_from_inet6num() throws IOException {
        createLogFile("REQUEST FROM:2000:3000:4000::/48\nPARAMS:");

        final String response = getUpdates("2000:3000:4000::/48", getDate());

        assertThat(response, containsString("\"host\":"));
        assertThat(response, containsString("\"id\":"));
    }

    // API calls

    private String getUpdates(final String searchTerm) throws IOException {
        return client
                .resource(String.format("http://localhost:%s/api/logs?search=%s&fromdate=&todate=&apiKey=%s", wSearchJettyConfig.getPort(), URLEncoder.encode(searchTerm, "ISO-8859-1"), apiKey))
                .get(String.class);
    }

    private String getUpdates(final String searchTerm, final String date) throws IOException {
        return client
                .resource(String.format("http://localhost:%s/api/logs?search=%s&date=%s&apiKey=%s", wSearchJettyConfig.getPort(), URLEncoder.encode(searchTerm, "ISO-8859-1"), date, apiKey))
                .get(String.class);
    }

    private String getUpdateIds(final String searchTerm, final String date) throws IOException {
        return client
                .resource(String.format("http://localhost:%s/api/logs/ids?search=%s&fromdate=%s&todate=&apiKey=%s", wSearchJettyConfig.getPort(), URLEncoder.encode(searchTerm, "ISO-8859-1"), date, apiKey))
                .get(String.class);
    }


    private String getCurrentUpdateLogsForId(final String updateId) {
        return client
                .resource(String.format("http://localhost:%s/api/logs/current/%s?apiKey=%s", wSearchJettyConfig.getPort(), updateId, apiKey))
                .get(String.class);
    }

    private String getCurrentUpdateLogsForIdAndDate(final String updateId, final String date) {
        return client
                .resource(String.format("http://localhost:%s/api/logs/current/%s/%s?apiKey=%s", wSearchJettyConfig.getPort(), date, updateId, apiKey))
                .get(String.class);
    }

    // helper methods

    private void createLogFile(final String data) throws IOException {
        final StringBuilder builder = new StringBuilder();
        builder.append(logDir)
                .append('/')
                .append(getDate())
                .append('/')
                .append(getTime())
                .append('.')
                .append(Math.random());

        final File fullDir = new File(builder.toString());
        fullDir.mkdirs();

        logFile = new File(fullDir, INPUT_FILE_NAME);
        final FileOutputStream fileOutputStream = new FileOutputStream(logFile);

        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(fileOutputStream), Charsets.ISO_8859_1));
            writer.write(data);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        logFileIndex.update();
    }

    private void createLogFileNew(final String data) throws IOException {
        final StringBuilder builder = new StringBuilder();
        builder.append(logDir)
                .append("/whois2/audit/")
                .append(getDate())
                .append('/')
                .append(getTime())
                .append('.')
                .append(Math.random());

        final File fullDir = new File(builder.toString());
        fullDir.mkdirs();

        logFile = new File(fullDir, INPUT_FILE_NAME);
        final FileOutputStream fileOutputStream = new FileOutputStream(logFile);

        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(fileOutputStream), Charsets.ISO_8859_1));
            writer.write(data);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        logFileIndex.update();
    }

    private String getLogDirName() throws IOException {
        final List<String> path = Lists.newArrayList(PATH_SPLITTER.split(logFile.getCanonicalPath()));
        return path.get(path.size() - 2);
    }

    private String getLogDirFullPathName() throws IOException {
        final List<String> path = Lists.newArrayList(PATH_SPLITTER.split(logFile.getCanonicalPath()));
        return Joiner.on("/").join(path.subList(0, path.size() - 1));
    }

    private String getDate() {
        return DATE_FORMAT.print(DateTime.now());
    }

    private String getTime() {
        return TIME_FORMAT.print(DateTime.now());
    }
}

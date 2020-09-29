package io.questdb.cutlass.http;

import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.TableWriter;
import io.questdb.cairo.pool.ex.EntryUnavailableException;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.network.NetworkFacade;
import io.questdb.network.NetworkFacadeImpl;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryIODispatcherTest {
    private static final Log LOG = LogFactory.getLog(RetryIODispatcherTest.class);

    private final String RequestHeaders = "Host: localhost:9000\r\n" +
            "Connection: keep-alive\r\n" +
            "Accept: */*\r\n" +
            "X-Requested-With: XMLHttpRequest\r\n" +
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.87 Safari/537.36\r\n" +
            "Sec-Fetch-Site: same-origin\r\n" +
            "Sec-Fetch-Mode: cors\r\n" +
            "Referer: http://localhost:9000/index.html\r\n" +
            "Accept-Encoding: gzip, deflate, br\r\n" +
            "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
            "\r\n";
    private final String ResponseHeaders =
            "HTTP/1.1 200 OK\r\n" +
                    "Server: questDB/1.0\r\n" +
                    "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Keep-Alive: timeout=5, max=10000\r\n" +
                    "\r\n";
    private static final String ValidImportRequest = "POST /upload HTTP/1.1\r\n" +
            "Host: localhost:9001\r\n" +
            "User-Agent: curl/7.64.0\r\n" +
            "Accept: */*\r\n" +
            "Content-Length: 437760673\r\n" +
            "Content-Type: multipart/form-data; boundary=------------------------27d997ca93d2689d\r\n" +
            "Expect: 100-continue\r\n" +
            "\r\n" +
            "--------------------------27d997ca93d2689d\r\n" +
            "Content-Disposition: form-data; name=\"schema\"; filename=\"schema.json\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "[\r\n" +
            "  {\r\n" +
            "    \"name\": \"date\",\r\n" +
            "    \"type\": \"DATE\",\r\n" +
            "    \"pattern\": \"d MMMM y.\",\r\n" +
            "    \"locale\": \"ru-RU\"\r\n" +
            "  }\r\n" +
            "]\r\n" +
            "\r\n" +
            "--------------------------27d997ca93d2689d\r\n" +
            "Content-Disposition: form-data; name=\"data\"; filename=\"fhv_tripdata_2017-02.csv\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "Dispatching_base_num,Pickup_DateTime,DropOff_datetime,PUlocationID,DOlocationID\r\n" +
            "B00008,2017-02-01 00:30:00,,,\r\n" +
            "B00008,2017-02-01 00:40:00,,,\r\n" +
            "B00009,2017-02-01 00:30:00,,,\r\n" +
            "B00013,2017-02-01 00:11:00,,,\r\n" +
            "B00013,2017-02-01 00:41:00,,,\r\n" +
            "B00013,2017-02-01 00:00:00,,,\r\n" +
            "B00013,2017-02-01 00:53:00,,,\r\n" +
            "B00013,2017-02-01 00:44:00,,,\r\n" +
            "B00013,2017-02-01 00:05:00,,,\r\n" +
            "B00013,2017-02-01 00:54:00,,,\r\n" +
            "B00014,2017-02-01 00:45:00,,,\r\n" +
            "B00014,2017-02-01 00:45:00,,,\r\n" +
            "B00014,2017-02-01 00:46:00,,,\r\n" +
            "B00014,2017-02-01 00:54:00,,,\r\n" +
            "B00014,2017-02-01 00:45:00,,,\r\n" +
            "B00014,2017-02-01 00:45:00,,,\r\n" +
            "B00014,2017-02-01 00:45:00,,,\r\n" +
            "B00014,2017-02-01 00:26:00,,,\r\n" +
            "B00014,2017-02-01 00:55:00,,,\r\n" +
            "B00014,2017-02-01 00:47:00,,,\r\n" +
            "B00014,2017-02-01 00:05:00,,,\r\n" +
            "B00014,2017-02-01 00:58:00,,,\r\n" +
            "B00014,2017-02-01 00:33:00,,,\r\n" +
            "B00014,2017-02-01 00:45:00,,,\r\n" +
            "\r\n" +
            "--------------------------27d997ca93d2689d--";
    private final String ValidImportResponse = "HTTP/1.1 200 OK\r\n" +
            "Server: questDB/1.0\r\n" +
            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            "05d7\r\n" +
            "+---------------------------------------------------------------------------------------------------------------+\r\n" +
            "|      Location:  |                          fhv_tripdata_2017-02.csv  |        Pattern  | Locale  |    Errors  |\r\n" +
            "|   Partition by  |                                              NONE  |                 |         |            |\r\n" +
            "+---------------------------------------------------------------------------------------------------------------+\r\n" +
            "|   Rows handled  |                                                24  |                 |         |            |\r\n" +
            "|  Rows imported  |                                                24  |                 |         |            |\r\n" +
            "+---------------------------------------------------------------------------------------------------------------+\r\n" +
            "|              0  |                                DispatchingBaseNum  |                   STRING  |         0  |\r\n" +
            "|              1  |                                    PickupDateTime  |                     DATE  |         0  |\r\n" +
            "|              2  |                                   DropOffDatetime  |                   STRING  |         0  |\r\n" +
            "|              3  |                                      PUlocationID  |                   STRING  |         0  |\r\n" +
            "|              4  |                                      DOlocationID  |                   STRING  |         0  |\r\n" +
            "+---------------------------------------------------------------------------------------------------------------+\r\n" +
            "\r\n" +
            "00\r\n" +
            "\r\n";
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testInsertWaitsWhenWriterLockedLoop() throws Exception {
        for (int i = 0; i < 10; i++) {
            System.out.println("*************************************************************************************");
            System.out.println("**************************         Run " + i + "            ********************************");
            System.out.println("*************************************************************************************");
            testInsertWaitsWhenWriterLocked();
            temp.delete();
            temp.create();
        }
    }

    @Test
    public void testInsertsIsPerformedWhenWriterLockedAndDisconnectedLoop() throws Exception {
        for (int i = 0; i < 10; i++) {
            System.out.println("*************************************************************************************");
            System.out.println("**************************         Run " + i + "            ********************************");
            System.out.println("*************************************************************************************");
            testInsertsIsPerformedWhenWriterLockedAndDisconnected();
            temp.delete();
            temp.create();
        }
    }

    @Test
    public void testImportWaitsWhenWriterLockedLoop() throws Exception {
        for (int i = 0; i < 10; i++) {
            System.out.println("*************************************************************************************");
            System.out.println("**************************         Run " + i + "            ********************************");
            System.out.println("*************************************************************************************");
            testImportWaitsWhenWriterLocked(0);
            temp.delete();
            temp.create();
        }
    }

    @Test
    public void testImportProcessedWhenClientDisconnectedLoop() throws Exception {
        for (int i = 0; i < 10; i++) {
            System.out.println("*************************************************************************************");
            System.out.println("**************************         Run " + i + "            ********************************");
            System.out.println("*************************************************************************************");
            testImportProcessedWhenClientDisconnected();
            temp.delete();
            temp.create();
        }
    }

    @Test
    public void testInsertWaitsExceedsRerunProcessingQueueSizeLoop() throws Exception {
        for (int i = 0; i < 10; i++) {
            System.out.println("*************************************************************************************");
            System.out.println("**************************         Run " + i + "            ********************************");
            System.out.println("*************************************************************************************");
            testInsertWaitsExceedsRerunProcessingQueueSize();
            temp.delete();
            temp.create();
        }
    }

    @Test
    public void testImportWaitsWhenWriterLockedWithSlowPeerLoop() throws Exception {
        for (int i = 0; i < 10; i++) {
            System.out.println("*************************************************************************************");
            System.out.println("**************************         Run " + i + "            ********************************");
            System.out.println("*************************************************************************************");
            testImportWaitsWhenWriterLocked(500);
            temp.delete();
            temp.create();
        }
    }

    @Test
    public void testImportRerunsExceedsRerunProcessingQueueSizeLoop() throws Exception {
        for (int i = 0; i < 10; i++) {
            System.out.println("*************************************************************************************");
            System.out.println("**************************         Run " + i + "            ********************************");
            System.out.println("*************************************************************************************");
            testImportRerunsExceedsRerunProcessingQueueSize(1000);
            temp.delete();
            temp.create();
        }
    }

    @Test
    public void testInsertWaitsWhenWriterLocked() throws Exception {
        final int parallelCount = 2;
        new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(parallelCount)
                .withHttpServerConfigBuilder(new HttpServerConfigurationBuilder())
                .withTelemetry(false)
                .run(engine -> {
                    // create table
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            "GET /query?query=%0A%0A%0Acreate+table+balances_x+(%0A%09cust_id+int%2C+%0A%09balance_ccy+symbol%2C+%0A%09balance+double%2C+%0A%09status+byte%2C+%0A%09timestamp+timestamp%0A)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                                    RequestHeaders,
                            ResponseHeaders +
                                    "0c\r\n" +
                                    "{\"ddl\":\"OK\"}\r\n" +
                                    "00\r\n" +
                                    "\r\n",
                            1,
                            0,
                            true,
                            false
                    );

                    TableWriter writer = lockWriter(engine, "balances_x");

                    final int insertCount = 10;
                    CountDownLatch countDownLatch = new CountDownLatch(parallelCount);
                    for (int i = 0; i < parallelCount; i++) {
                        new Thread(() -> {
                            try {
                                for (int r = 0; r < insertCount; r++) {
                                    // insert one record
                                    try {
                                        sendAndReceive(
                                                NetworkFacadeImpl.INSTANCE,
                                                "GET /query?query=%0A%0Ainsert+into+balances_x+(cust_id%2C+balance_ccy%2C+balance%2C+timestamp)+values+(1%2C+%27USD%27%2C+1500.00%2C+6000000001)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                                                        RequestHeaders,
                                                ResponseHeaders +
                                                        "0c\r\n" +
                                                        "{\"ddl\":\"OK\"}\r\n" +
                                                        "00\r\n" +
                                                        "\r\n",
                                                1,
                                                0,
                                                false,
                                                false
                                        );
                                    } catch (Exception e) {
                                        LOG.error().$("Failed execute insert http request. Server error ").$(e);
                                    }
                                }
                            } finally {
                                countDownLatch.countDown();
                            }
                        }).start();
                    }

                    boolean finished = countDownLatch.await(200, TimeUnit.MILLISECONDS);

                    // Cairo engine should not allow second writer to be opened on the same table
                    // Cairo is expected to have finished == false
                    Assert.assertFalse(finished);

                    writer.close();
                    countDownLatch.await();

                    // check if we have parallelCount x insertCount  records
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            "GET /query?query=select+count(*)+from+balances_x&count=true HTTP/1.1\r\n" +
                                    RequestHeaders,
                            ResponseHeaders +
                                    "71\r\n" +
                                    "{\"query\":\"select count(*) from balances_x\",\"columns\":[{\"name\":\"count\",\"type\":\"LONG\"}],\"dataset\":[[" + parallelCount * insertCount + "]],\"count\":1}\r\n" +
                                    "00\r\n" +
                                    "\r\n",
                            1,
                            0,
                            false,
                            false
                    );
                });
    }

    @Test
    public void testInsertWaitsExceedsRerunProcessingQueueSize() throws Exception {
        final int rerunProcessingQueueSize = 1;
        final int parallelCount = 4;
        new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(parallelCount)
                .withHttpServerConfigBuilder(new HttpServerConfigurationBuilder().withRerunProcessingQueueSize(rerunProcessingQueueSize))
                .withTelemetry(false)
                .run(engine -> {
                    // create table
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            "GET /query?query=%0A%0A%0Acreate+table+balances_x+(%0A%09cust_id+int%2C+%0A%09balance_ccy+symbol%2C+%0A%09balance+double%2C+%0A%09status+byte%2C+%0A%09timestamp+timestamp%0A)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                                    RequestHeaders,
                            ResponseHeaders +
                                    "0c\r\n" +
                                    "{\"ddl\":\"OK\"}\r\n" +
                                    "00\r\n" +
                                    "\r\n",
                            1,
                            0,
                            true,
                            false
                    );

                    TableWriter writer = lockWriter(engine, "balances_x");

                    final int insertCount = rerunProcessingQueueSize * 10;
                    CountDownLatch countDownLatch = new CountDownLatch(parallelCount);
                    AtomicInteger fails = new AtomicInteger();
                    for (int i = 0; i < parallelCount; i++) {
                        new Thread(() -> {
                            try {
                                for (int r = 0; r < insertCount; r++) {
                                    // insert one record
                                    try {
                                        sendAndReceive(
                                                NetworkFacadeImpl.INSTANCE,
                                                "GET /query?query=%0A%0Ainsert+into+balances_x+(cust_id%2C+balance_ccy%2C+balance%2C+timestamp)+values+(1%2C+%27USD%27%2C+1500.00%2C+6000000001)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                                                        RequestHeaders,
                                                ResponseHeaders +
                                                        "0c\r\n" +
                                                        "{\"ddl\":\"OK\"}\r\n" +
                                                        "00\r\n" +
                                                        "\r\n",
                                                1,
                                                0,
                                                false,
                                                true
                                        );
                                    } catch (AssertionError ase) {
                                        fails.incrementAndGet();
                                    } catch (Exception e) {
                                        LOG.error().$("Failed execute insert http request. Server error ").$(e);
                                    }
                                }
                            } finally {
                                countDownLatch.countDown();
                            }
                        }).start();
                    }

                    boolean finished = countDownLatch.await(200, TimeUnit.MILLISECONDS);
                    Assert.assertFalse(finished);

                    writer.close();
                    if (!countDownLatch.await(5000, TimeUnit.MILLISECONDS)) {
                        Assert.fail("Wait to process retries exceeded timeout");
                    }

                    // check if we have parallelCount x insertCount  records
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            "GET /query?query=select+count(*)+from+balances_x&count=true HTTP/1.1\r\n" +
                                    RequestHeaders,
                            ResponseHeaders +
                                    "71\r\n" +
                                    "{\"query\":\"select count(*) from balances_x\",\"columns\":[{\"name\":\"count\",\"type\":\"LONG\"}],\"dataset\":[[" + (parallelCount * insertCount - fails.get()) + "]],\"count\":1}\r\n" +
                                    "00\r\n" +
                                    "\r\n",
                            1,
                            0,
                            false,
                            false
                    );
                });

    }

    // TODO: investigate failure
    @Test
    @Ignore
    public void queryAndDisconnect() throws Exception {
        final int parallelCount = 4;
        final int requestMult = 4;
        new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(parallelCount)
                .withHttpServerConfigBuilder(new HttpServerConfigurationBuilder())
                .withTelemetry(false)
                .run(engine -> {
                    // create table
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            "GET /query?query=%0A%0A%0Acreate+table+balances_x+(%0A%09cust_id+int%2C+%0A%09balance_ccy+symbol%2C+%0A%09balance+double%2C+%0A%09status+byte%2C+%0A%09timestamp+timestamp%0A)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                                    RequestHeaders,
                            ResponseHeaders +
                                    "0c\r\n" +
                                    "{\"ddl\":\"OK\"}\r\n" +
                                    "00\r\n" +
                                    "\r\n",
                            1,
                            0,
                            true,
                            false
                    );

                    CountDownLatch countDownLatch = new CountDownLatch(parallelCount);
                    Thread[] threads = new Thread[parallelCount * requestMult];
                    for (int i = 0; i < parallelCount; i++) {
                        int finalI = i;
                        threads[i] = new Thread(() -> {
                            try {
                                for (int j = 0; j < requestMult; j++) {
                                    // insert one record
                                    try {
                                        sendAndReceive(
                                                NetworkFacadeImpl.INSTANCE,
                                                "GET /query?query=select+count(*)+from+balances_x&count=true HTTP/1.1\r\n" +
                                                        RequestHeaders,
                                                "",
                                                1,
                                                5,
                                                true,
                                                false
                                        );

                                        sendAndReceive(
                                                NetworkFacadeImpl.INSTANCE,
                                                "GET /query?query=select+123,37463,38934,983,99203,102932,40954,count(*)+count123+from+balances_x&count=true HTTP/1.1\r\n" +
                                                        RequestHeaders,
                                                "",
                                                1,
                                                5,
                                                true,
                                                false
                                        );
                                    } catch (Exception e) {
                                        LOG.error().$("Failed execute insert http request. Server error ").$(e);
                                    }
                                }
                            } finally {
                                countDownLatch.countDown();
                            }
                        });
                        threads[i].start();
                    }
                    countDownLatch.await();
                    Thread.sleep(1000);
                });
    }

    @Test
    public void testInsertsIsPerformedWhenWriterLockedAndDisconnected() throws Exception {
        final int parallelCount = 4;
        new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(parallelCount)
                .withHttpServerConfigBuilder(new HttpServerConfigurationBuilder())
                .withTelemetry(false)
                .run(engine -> {
                    // create table
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            "GET /query?query=%0A%0A%0Acreate+table+balances_x+(%0A%09cust_id+int%2C+%0A%09balance_ccy+symbol%2C+%0A%09balance+double%2C+%0A%09status+byte%2C+%0A%09timestamp+timestamp%0A)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                                    RequestHeaders,
                            ResponseHeaders +
                                    "0c\r\n" +
                                    "{\"ddl\":\"OK\"}\r\n" +
                                    "00\r\n" +
                                    "\r\n",
                            1,
                            0,
                            true,
                            false
                    );

                    TableWriter writer = lockWriter(engine, "balances_x");
                    CountDownLatch countDownLatch = new CountDownLatch(parallelCount);
                    Thread[] threads = new Thread[parallelCount];
                    for (int i = 0; i < parallelCount; i++) {
                        int finalI = i;
                        threads[i] = new Thread(() -> {
                            try {
                                // insert one record
                                try {
                                    Thread.sleep(finalI * 5);
                                    sendAndReceive(
                                            NetworkFacadeImpl.INSTANCE,
                                            "GET /query?query=%0A%0Ainsert+into+balances_x+(cust_id%2C+balance_ccy%2C+balance%2C+timestamp)+values+(" + finalI + "%2C+%27USD%27%2C+1500.00%2C+6000000001)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                                                    RequestHeaders,
                                            "",
                                            1,
                                            200,
                                            false,
                                            false
                                    );
                                } catch (Exception e) {
                                    LOG.error().$("Failed execute insert http request. Server error ").$(e);
                                }
                            } finally {
                                countDownLatch.countDown();
                            }
                        });
                        threads[i].start();
                    }

                    countDownLatch.await();

                    // Cairo engine should not allow second writer to be opened on the same table, all requests should wait for the writer to be available
                    writer.close();

                    // check if we have parallelCount x insertCount  records
                    int expectedCount = parallelCount;
                    int waitCount = 1000 / 50 * expectedCount;
                    for (int i = 0; i < waitCount; i++) {

                        try {
                            sendAndReceive(
                                    NetworkFacadeImpl.INSTANCE,
                                    "GET /query?query=select+count()+from+balances_x&count=true HTTP/1.1\r\n" +
                                            RequestHeaders,
                                    ResponseHeaders +
                                            "6f\r\n" +
                                            "{\"query\":\"select count() from balances_x\",\"columns\":[{\"name\":\"count\",\"type\":\"LONG\"}],\"dataset\":[[" + expectedCount + "]],\"count\":1}\r\n" +
                                            "00\r\n" +
                                            "\r\n",
                                    1,
                                    0,
                                    false,
                                    false
                            );
                            return;
                        } catch (ComparisonFailure e) {
                            if (i < waitCount - 1) {
                                Thread.sleep(50);
                            } else {
                                throw e;
                            }

                        }
                    }
                });
    }

    @Test
    public void testImportWaitsWhenWriterLocked() throws Exception {
        testImportWaitsWhenWriterLocked(0);
    }

    public void testImportWaitsWhenWriterLocked(int slowNetAfterSending) throws Exception {
        final int parallelCount = 2;
        new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(2)
                .withHttpServerConfigBuilder(new HttpServerConfigurationBuilder()
                        .withNetwork(getSendDelayNetworkFacade(slowNetAfterSending)))
                .run((engine) -> {
                    // create table and do 1 import
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            ValidImportRequest,
                            ValidImportResponse,
                            1,
                            0,
                            false,
                            false
                    );

                    TableWriter writer = lockWriter(engine, "fhv_tripdata_2017-02.csv");
                    final int validRequestRecordCount = 24;
                    final int insertCount = 1;
                    CountDownLatch countDownLatch = new CountDownLatch(parallelCount);
                    for (int i = 0; i < parallelCount; i++) {
                        int finalI = i;
                        new Thread(() -> {
                            try {
                                for (int r = 0; r < insertCount; r++) {
                                    // insert one record
                                    try {
                                        sendAndReceive(
                                                NetworkFacadeImpl.INSTANCE,
                                                ValidImportRequest,
                                                ValidImportResponse,
                                                1,
                                                0,
                                                false,
                                                false
                                        );
                                    } catch (Exception e) {
                                        LOG.error().$("Failed execute insert http request. Server error ").$(e).$();
                                    }
                                }
                            } finally {
                                countDownLatch.countDown();
                            }
                            LOG.info().$("Stopped thread ").$(finalI).$();
                        }).start();
                    }

                    boolean finished = countDownLatch.await(100, TimeUnit.MILLISECONDS);

                    // Cairo engine should not allow second writer to be opened on the same table
                    // Cairo is expected to have finished == false
                    Assert.assertFalse(finished);

                    writer.close();
                    if (!countDownLatch.await(5000, TimeUnit.MILLISECONDS)) {
                        Assert.fail("Imports did not finish within reasonable time");
                    }

                    // check if we have parallelCount x insertCount  records
                    LOG.info().$("Requesting row count").$();
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            "GET /query?query=select+count(*)+from+%22fhv_tripdata_2017-02.csv%22&count=true HTTP/1.1\r\n" +
                                    RequestHeaders,
                            ResponseHeaders +
                                    "83\r\n" +
                                    "{\"query\":\"select count(*) from \\\"fhv_tripdata_2017-02.csv\\\"\",\"columns\":[{\"name\":\"count\",\"type\":\"LONG\"}],\"dataset\":[[" + (parallelCount + 1) * validRequestRecordCount + "]],\"count\":1}\r\n" +
                                    "00\r\n" +
                                    "\r\n",
                            1,
                            0,
                            false,
                            false
                    );

                });
    }

    @Test
    public void testImportProcessedWhenClientDisconnected() throws Exception {
        final int parallelCount = 2;
        new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(2)
                .withHttpServerConfigBuilder(new HttpServerConfigurationBuilder())
                .withTelemetry(false)
                .run((engine) -> {
                    // create table and do 1 import
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            ValidImportRequest,
                            ValidImportResponse,
                            1,
                            0,
                            false,
                            false
                    );

                    TableWriter writer = lockWriter(engine, "fhv_tripdata_2017-02.csv");

                    final int validRequestRecordCount = 24;
                    final int insertCount = 1;
                    CountDownLatch countDownLatch = new CountDownLatch(parallelCount);
                    for (int i = 0; i < parallelCount; i++) {
                        int finalI = i;
                        new Thread(() -> {
                            try {
                                for (int r = 0; r < insertCount; r++) {
                                    // insert one record
                                    try {
                                        sendAndReceive(
                                                NetworkFacadeImpl.INSTANCE,
                                                ValidImportRequest,
                                                "",
                                                1,
                                                0,
                                                false,
                                                false
                                        );
                                    } catch (Exception e) {
                                        LOG.error().$("Failed execute insert http request. Server error ").$(e).$();
                                    }
                                }
                            } finally {
                                countDownLatch.countDown();
                            }
                            LOG.info().$("Stopped thread ").$(finalI).$();
                        }).start();
                    }

                    countDownLatch.await();

                    // Cairo engine should not allow second writer to be opened on the same table, all requests should wait for the writer to be available
                    writer.close();

                    for (int i = 0; i < 20; i++) {

                        try {
                            // check if we have parallelCount x insertCount  records
                            sendAndReceive(
                                    NetworkFacadeImpl.INSTANCE,
                                    "GET /query?query=select+count(*)+from+%22fhv_tripdata_2017-02.csv%22&count=true HTTP/1.1\r\n" +
                                            RequestHeaders,
                                    ResponseHeaders +
                                            "83\r\n" +
                                            "{\"query\":\"select count(*) from \\\"fhv_tripdata_2017-02.csv\\\"\",\"columns\":[{\"name\":\"count\",\"type\":\"LONG\"}],\"dataset\":[[" + (parallelCount + 1) * validRequestRecordCount + "]],\"count\":1}\r\n" +
                                            "00\r\n" +
                                            "\r\n",
                                    1,
                                    0,
                                    false,
                                    false
                            );
                            return;
                        } catch (ComparisonFailure e) {
                            if (i < 9) {
                                Thread.sleep(50);
                            } else {
                                throw e;
                            }

                        }
                    }

                });
    }

    @Test
    public void testImportRerunsExceedsRerunProcessingQueueSize() throws Exception {
        testImportRerunsExceedsRerunProcessingQueueSize(0);
    }

    public void testImportRerunsExceedsRerunProcessingQueueSize(int startDelay) throws Exception {
        final int rerunProcessingQueueSize = 1;
        final int parallelCount = 4;

        new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withWorkerCount(2)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder()
                                .withNetwork(getSendDelayNetworkFacade(startDelay))
                                .withRerunProcessingQueueSize(rerunProcessingQueueSize)
                )
                .run(engine -> {
                    // create table and do 1 import
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            ValidImportRequest,
                            ValidImportResponse,
                            1,
                            0,
                            false,
                            false
                    );

                    TableWriter writer = lockWriter(engine, "fhv_tripdata_2017-02.csv");
                    final int validRequestRecordCount = 24;
                    final int insertCount = 4;
                    AtomicInteger failedImports = new AtomicInteger();
                    CountDownLatch countDownLatch = new CountDownLatch(parallelCount);
                    for (int i = 0; i < parallelCount; i++) {
                        int finalI = i;
                        new Thread(() -> {
                            try {
                                for (int r = 0; r < insertCount; r++) {
                                    // insert one record
                                    try {
                                        sendAndReceive(
                                                NetworkFacadeImpl.INSTANCE,
                                                ValidImportRequest,
                                                ValidImportResponse,
                                                1,
                                                0,
                                                false,
                                                false
                                        );
                                    } catch (AssertionError e) {
                                        failedImports.incrementAndGet();
                                    } catch (Exception e) {
                                        LOG.error().$("Failed execute insert http request. Server error ").$(e).$();
                                    }
                                }
                            } finally {
                                countDownLatch.countDown();
                            }
                            LOG.info().$("Stopped thread ").$(finalI).$();
                        }).start();
                    }

                    boolean finished = countDownLatch.await(100, TimeUnit.MILLISECONDS);
                    Assert.assertFalse(finished);

                    writer.close();

                    if (!countDownLatch.await(5000, TimeUnit.MILLISECONDS)) {
                        Assert.fail("Imports did not finish within reasonable time");
                    }

                    // check if we have parallelCount x insertCount  records
                    LOG.info().$("Requesting row count").$();
                    sendAndReceive(
                            NetworkFacadeImpl.INSTANCE,
                            "GET /query?query=select+count(*)+from+%22fhv_tripdata_2017-02.csv%22&count=true HTTP/1.1\r\n" +
                                    RequestHeaders,
                            ResponseHeaders +
                                    "84\r\n" +
                                    "{\"query\":\"select count(*) from \\\"fhv_tripdata_2017-02.csv\\\"\",\"columns\":[{\"name\":\"count\",\"type\":\"LONG\"}],\"dataset\":[[" + (parallelCount * insertCount + 1 - failedImports.get()) * validRequestRecordCount + "]],\"count\":1}\r\n" +
                                    "00\r\n" +
                                    "\r\n",
                            1,
                            0,
                            false,
                            false
                    );

                });
    }

    @NotNull
    private NetworkFacade getSendDelayNetworkFacade(int startDelayDelayAfter) {
        if (startDelayDelayAfter == 0)
            return NetworkFacadeImpl.INSTANCE;

        return new NetworkFacadeImpl() {
            int totalSent = 0;

            @Override
            public int send(long fd, long buffer, int bufferLen) {
                if (bufferLen > 0) {
                    if (totalSent == startDelayDelayAfter) {
                        totalSent = 0;
                        return 0;
                    }

                    int result = super.send(fd, buffer, Math.min(bufferLen, startDelayDelayAfter - totalSent));
                    totalSent += result;
                    return result;
                }
                return 0;
            }
        };
    }

    @NotNull
    private TableWriter lockWriter(CairoEngine engine, String tableName) throws InterruptedException {
        TableWriter writer = null;
        for (int i = 0; i < 10; i++) {
            try {
                writer = engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, tableName);
                break;
            } catch (EntryUnavailableException e) {
                Thread.sleep(10);
            }
        }

        if (writer == null) {
            Assert.fail("Cannot lock writer in a reasonable time");
        }
        return writer;
    }

    public void testImport(
            String response,
            String request,
            NetworkFacade nf,
            boolean expectDisconnect,
            int requestCount
    ) throws Exception {
        new HttpQueryTestBuilder()
                .withTempFolder(temp)
                .withHttpServerConfigBuilder(
                        new HttpServerConfigurationBuilder()
                                .withServerKeepAlive(true)
                                .withHttpProtocolVersion("HTTP/1.1 ")
                )
                .run(engine -> {
                    sendAndReceive(
                            nf,
                            request,
                            response,
                            requestCount,
                            0,
                            false,
                            expectDisconnect
                    );
                });
    }

    private void sendAndReceive(
            NetworkFacade nf,
            String request,
            String response,
            int requestCount,
            long pauseBetweenSendAndReceive,
            boolean printOnly,
            boolean expectDisconnect
    ) throws InterruptedException {
        new SendAndReceiveRequestBuilder()
                .withNetworkFacade(nf)
                .withExpectDisconnect(expectDisconnect)
                .withPrintOnly(printOnly)
                .withRequestCount(requestCount)
                .withPauseBetweenSendAndReceive(pauseBetweenSendAndReceive)
                .execute(request, response);
    }
}

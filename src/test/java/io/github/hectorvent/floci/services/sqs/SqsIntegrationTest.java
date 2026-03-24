package io.github.hectorvent.floci.services.sqs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqsIntegrationTest {

    private static String queueUrl;

    @Test
    @Order(1)
    void createQueue() {
        queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "integration-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<QueueUrl>"))
            .body(containsString("integration-test-queue"))
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    @Test
    @Order(2)
    void getQueueUrl() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "integration-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("integration-test-queue"));
    }

    @Test
    @Order(3)
    void listQueues() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListQueues")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("integration-test-queue"));
    }

    @Test
    @Order(4)
    void sendMessage() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "Hello from integration test!")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"))
            .body(containsString("<MD5OfMessageBody>"));
    }

    @Test
    @Order(5)
    void receiveMessage() {
        String receiptHandle = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("Hello from integration test!"))
            .body(containsString("<ReceiptHandle>"))
            .extract().xmlPath().getString(
                "ReceiveMessageResponse.ReceiveMessageResult.Message.ReceiptHandle");

        // Store for delete test — use static field
        SqsIntegrationTest.receiptHandle = receiptHandle;
    }

    private static String receiptHandle;

    @Test
    @Order(6)
    void deleteMessage() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("ReceiptHandle", receiptHandle)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeleteMessageResponse>"));
    }

    @Test
    @Order(7)
    void receiveMessageAfterDeleteReturnsEmpty() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("VisibilityTimeout", "0")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Message>")));
    }

    @Test
    @Order(8)
    void sendAndPurgeQueue() {
        // Send some messages
        for (int i = 0; i < 3; i++) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MessageBody", "purge-msg-" + i)
            .when()
                .post("/");
        }

        // Purge
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "PurgeQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify empty
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "10")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Message>")));
    }

    @Test
    @Order(9)
    void getQueueAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "All")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Attribute>"))
            .body(containsString("QueueArn"));
    }

    @Test
    @Order(10)
    void deleteQueue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify it's gone
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "integration-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(11)
    void sendMessageViaJsonQueuePath() {
        // First, create a queue for this test
        String url = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "json-routing-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        // Send a message via POST /{accountId}/{queueName} with JSON 1.0 protocol
        given()
            .contentType("application/x-amz-json-1.0")
            .header("X-Amz-Target", "AmazonSQS.SendMessage")
            .body("{\"QueueUrl\":\"" + url + "\",\"MessageBody\":\"Hello via JSON 1.0 queue path\"}")
        .when()
            .post("/000000000000/json-routing-test-queue")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue())
            .body("MD5OfMessageBody", notNullValue());
    }

    @Test
    void unsupportedAction() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UnsupportedAction")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("UnsupportedOperation"));
    }
}

package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3MultipartIntegrationTest {

    private static final String BUCKET = "multipart-test-bucket";
    private static final String KEY = "large-file.bin";
    private static String uploadId;

    @Test
    @Order(1)
    void createBucket() {
        given()
            .when().put("/" + BUCKET)
            .then().statusCode(200);
    }

    @Test
    @Order(2)
    void initiateMultipartUpload() {
        uploadId = given()
            .contentType("application/octet-stream")
            .header("x-amz-meta-owner", "team-a")
            .header("x-amz-storage-class", "STANDARD_IA")
        .when()
            .post("/" + BUCKET + "/" + KEY + "?uploads")
        .then()
            .statusCode(200)
            .body(containsString("<UploadId>"))
            .body(containsString("<Bucket>" + BUCKET + "</Bucket>"))
            .body(containsString("<Key>" + KEY + "</Key>"))
            .extract().xmlPath().getString(
                "InitiateMultipartUploadResult.UploadId");
    }

    @Test
    @Order(3)
    void uploadPart1() {
        given()
            .body("Part1Data-Hello")
        .when()
            .put("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(4)
    void uploadPart2() {
        given()
            .body("Part2Data-World")
        .when()
            .put("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId + "&partNumber=2")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(5)
    void listMultipartUploads() {
        given()
        .when()
            .get("/" + BUCKET + "?uploads")
        .then()
            .statusCode(200)
            .body(containsString("<UploadId>" + uploadId + "</UploadId>"))
            .body(containsString("<Key>" + KEY + "</Key>"));
    }

    @Test
    @Order(6)
    void completeMultipartUpload() {
        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                    <Part><PartNumber>2</PartNumber><ETag>etag2</ETag></Part>
                </CompleteMultipartUpload>""";

        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .body(containsString("<CompleteMultipartUploadResult"))
            .body(containsString("<ETag>"))
            .body(containsString("-2")); // Composite ETag ends with -2
    }

    @Test
    @Order(7)
    void getCompletedObject() {
        given()
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .header("x-amz-meta-owner", equalTo("team-a"))
            .header("x-amz-storage-class", equalTo("STANDARD_IA"))
            .body(equalTo("Part1Data-HelloPart2Data-World"));
    }

    @Test
    @Order(8)
    void getMultipartObjectAttributes() {
        given()
            .header("x-amz-object-attributes", "ObjectParts,Checksum,StorageClass")
            .header("x-amz-max-parts", 1)
        .when()
            .get("/" + BUCKET + "/" + KEY + "?attributes")
        .then()
            .statusCode(200)
            .body(containsString("<GetObjectAttributesResponse"))
            .body(containsString("<StorageClass>STANDARD_IA</StorageClass>"))
            .body(containsString("<ObjectParts>"))
            .body(containsString("<PartsCount>2</PartsCount>"))
            .body(containsString("<ChecksumSHA256>"));
    }

    @Test
    @Order(9)
    void multipartUploadNoLongerListed() {
        given()
        .when()
            .get("/" + BUCKET + "?uploads")
        .then()
            .statusCode(200)
            .body(not(containsString("<UploadId>")));
    }

    @Test
    @Order(10)
    void abortMultipartUpload() {
        // Initiate new upload
        String newUploadId = given()
            .when()
                .post("/" + BUCKET + "/abort-test.bin?uploads")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        // Upload a part
        given()
            .body("some data")
        .when()
            .put("/" + BUCKET + "/abort-test.bin?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        // Abort
        given()
        .when()
            .delete("/" + BUCKET + "/abort-test.bin?uploadId=" + newUploadId)
        .then()
            .statusCode(204);

        // Verify upload is gone
        given()
        .when()
            .get("/" + BUCKET + "?uploads")
        .then()
            .statusCode(200)
            .body(not(containsString(newUploadId)));
    }

    @Test
    @Order(11)
    void listPartsOfActiveUpload() {
        // Initiate a new upload for list-parts testing
        String lpUploadId = given()
            .when()
                .post("/" + BUCKET + "/list-parts-test.bin?uploads")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        // Upload part 1
        given()
            .body("PartOneData")
        .when()
            .put("/" + BUCKET + "/list-parts-test.bin?uploadId=" + lpUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        // Upload part 2
        given()
            .body("PartTwoDataLonger")
        .when()
            .put("/" + BUCKET + "/list-parts-test.bin?uploadId=" + lpUploadId + "&partNumber=2")
        .then()
            .statusCode(200);

        // List parts
        given()
        .when()
            .get("/" + BUCKET + "/list-parts-test.bin?uploadId=" + lpUploadId)
        .then()
            .statusCode(200)
            .body(containsString("<ListPartsResult"))
            .body(containsString("<Bucket>" + BUCKET + "</Bucket>"))
            .body(containsString("<Key>list-parts-test.bin</Key>"))
            .body(containsString("<UploadId>" + lpUploadId + "</UploadId>"))
            .body(containsString("<PartNumber>1</PartNumber>"))
            .body(containsString("<PartNumber>2</PartNumber>"))
            .body(containsString("<Size>11</Size>"))
            .body(containsString("<Size>17</Size>"))
            .body(containsString("<ETag>"))
            .body(containsString("<IsTruncated>false</IsTruncated>"))
            .body(containsString("<MaxParts>1000</MaxParts>"));

        // Abort to clean up
        given().when().delete("/" + BUCKET + "/list-parts-test.bin?uploadId=" + lpUploadId).then().statusCode(204);
    }

    @Test
    @Order(12)
    void listPartsWithPagination() {
        String lpUploadId = given()
            .when()
                .post("/" + BUCKET + "/list-parts-page.bin?uploads")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        // Upload 3 parts
        for (int i = 1; i <= 3; i++) {
            given()
                .body("data" + i)
            .when()
                .put("/" + BUCKET + "/list-parts-page.bin?uploadId=" + lpUploadId + "&partNumber=" + i)
            .then()
                .statusCode(200);
        }

        // List with max-parts=1
        given()
        .when()
            .get("/" + BUCKET + "/list-parts-page.bin?uploadId=" + lpUploadId + "&max-parts=1")
        .then()
            .statusCode(200)
            .body(containsString("<IsTruncated>true</IsTruncated>"))
            .body(containsString("<MaxParts>1</MaxParts>"))
            .body(containsString("<NextPartNumberMarker>1</NextPartNumberMarker>"))
            .body(containsString("<PartNumber>1</PartNumber>"))
            .body(not(containsString("<PartNumber>2</PartNumber>")));

        // Abort to clean up
        given().when().delete("/" + BUCKET + "/list-parts-page.bin?uploadId=" + lpUploadId).then().statusCode(204);
    }

    @Test
    @Order(13)
    void listPartsNonExistentUpload() {
        given()
        .when()
            .get("/" + BUCKET + "/" + KEY + "?uploadId=non-existent-upload-id")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchUpload"));
    }

    @Test
    @Order(14)
    void listPartsWithMarker() {
        String lpUploadId = given()
            .when()
                .post("/" + BUCKET + "/list-parts-marker.bin?uploads")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        // Upload 2 parts
        given()
            .body("first")
        .when()
            .put("/" + BUCKET + "/list-parts-marker.bin?uploadId=" + lpUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        given()
            .body("second")
        .when()
            .put("/" + BUCKET + "/list-parts-marker.bin?uploadId=" + lpUploadId + "&partNumber=2")
        .then()
            .statusCode(200);

        // List with part-number-marker=1, should only return part 2
        given()
        .when()
            .get("/" + BUCKET + "/list-parts-marker.bin?uploadId=" + lpUploadId + "&part-number-marker=1")
        .then()
            .statusCode(200)
            .body(containsString("<PartNumberMarker>1</PartNumberMarker>"))
            .body(containsString("<PartNumber>2</PartNumber>"))
            .body(not(containsString("<PartNumber>1</PartNumber>")))
            .body(containsString("<NextPartNumberMarker>2</NextPartNumberMarker>"));

        // Abort to clean up
        given().when().delete("/" + BUCKET + "/list-parts-marker.bin?uploadId=" + lpUploadId).then().statusCode(204);
    }

    @Test
    @Order(15)
    void cleanUp() {
        given().when().delete("/" + BUCKET + "/" + KEY).then().statusCode(204);
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }
}

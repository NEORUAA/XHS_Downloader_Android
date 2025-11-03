package com.neoruaa.xhsdn;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class LivePhotoCreator {
    private static final String TAG = "LivePhotoCreator";

    /**
     * Creates a live photo by embedding video into image with XMP metadata
     * @param imageFile The image file to use as the primary content
     * @param videoFile The video file to embed
     * @param outputFile The output live photo file
     * @return True if successful, false otherwise
     */
    public static boolean createLivePhoto(File imageFile, File videoFile, File outputFile) {
        try {
            Log.d(TAG, "Creating live photo from image: " + imageFile.getAbsolutePath() + 
                   " (size: " + imageFile.length() + " bytes) and video: " + videoFile.getAbsolutePath() + 
                   " (size: " + videoFile.length() + " bytes) -> output: " + outputFile.getAbsolutePath());
            
            // Read the image and video files
            byte[] imageBytes = readFileToBytes(imageFile);
            byte[] videoBytes = readFileToBytes(videoFile);
            
            Log.d(TAG, "Read " + imageBytes.length + " bytes from image and " + videoBytes.length + " bytes from video");
            
            // Create the live photo by combining image with XMP and appending the video
            byte[] livePhotoBytes = createLivePhotoBytes(imageBytes, videoBytes);
            
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(livePhotoBytes);
            }
            
            Log.d(TAG, "Successfully created live photo with total size: " + outputFile.length() + " bytes");
            
            // Verify that the output file can be read as a valid image by attempting to get its length
            if (!outputFile.exists() || outputFile.length() == 0) {
                Log.e(TAG, "Output file is invalid (does not exist or has zero size)");
                if (outputFile.exists()) {
                    outputFile.delete();  // Clean up invalid file
                }
                return false;
            }
            
            // Additional verification: check if the created file is a valid JPEG with proper structure
            if (!isValidJpegWithXmp(outputFile)) {
                Log.e(TAG, "Created live photo file is not a valid JPEG with proper XMP structure");
                if (outputFile.exists()) {
                    outputFile.delete();  // Clean up invalid file
                }
                return false;
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error creating live photo: " + e.getMessage());
            e.printStackTrace();
            // If the file was created but is invalid, delete it
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        }
    }
    
    /**
     * Checks if the file is a valid JPEG with proper structure for Motion Photo
     * @param file The file to check
     * @return true if valid JPEG with XMP, false otherwise 
     */
    private static boolean isValidJpegWithXmp(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[10];
            int bytesRead = fis.read(header);
            if (bytesRead < 2) {
                return false;
            }
            
            // Check for JPEG SOI marker (0xFFD8)
            if (header[0] != (byte) 0xFF || header[1] != (byte) 0xD8) {
                Log.d(TAG, "File does not have valid JPEG SOI marker");
                return false;
            }
            
            // Read entire file to check for XMP metadata
            byte[] fileBytes = readFileToBytes(file);
            String fileContent = new String(fileBytes, 0, Math.min(fileBytes.length, 10240)); // Check first 10KB for XMP
            
            // Check if XMP metadata exists in the file
            if (!fileContent.toLowerCase().contains("xmpmeta") || 
                !fileContent.toLowerCase().contains("motionphoto")) {
                Log.d(TAG, "File does not contain valid XMP metadata for Motion Photo");
                return false;
            }
            
            // Additional checks could be added here if needed
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error validating JPEG file: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Creates live photo bytes by combining image and video with XMP metadata
     * @param imageBytes The original image bytes
     * @param videoBytes The video bytes to embed
     * @return Combined live photo bytes
     */
    private static byte[] createLivePhotoBytes(byte[] imageBytes, byte[] videoBytes) throws IOException {
        // Find the position where video should start (at the end of the image/jpeg part)
        int videoStartPos = findVideoStartPos(imageBytes);
        
        // Generate XMP metadata with the correct video size
        String xmpMetadata = generateXMPMetadata(videoBytes.length);
        byte[] xmpBytes = xmpMetadata.getBytes("UTF-8");
        
        // Create the APP1 XMP segment
        byte[] xmpSegment = createXmpApp1Segment(xmpBytes);
        
        // Find position for XMP insertion (after SOI and other APP segments, but before image data)
        int xmpInsertPosition = findXmpInsertPosition(imageBytes);
        if (xmpInsertPosition == -1) {
            // If no appropriate position found, insert after SOI (position 2)
            xmpInsertPosition = 2;
        }
        
        // Insert XMP into the image
        byte[] imageWithXmp = insertXMPIntoJpeg(imageBytes, xmpSegment, xmpInsertPosition);
        
        // Calculate new video start position after XMP insertion
        int newVideoStartPos = videoStartPos + xmpSegment.length;
        
        // Combine the image with XMP + video at the end
        byte[] result = new byte[imageWithXmp.length + videoBytes.length];
        System.arraycopy(imageWithXmp, 0, result, 0, imageWithXmp.length);
        System.arraycopy(videoBytes, 0, result, imageWithXmp.length, videoBytes.length);
        
        return result;
    }
    
    /**
     * Finds the position where the video starts (typically at the end of the JPEG image data)
     * This helps in calculating the correct MicroVideoOffset value in XMP
     * @param imageBytes The JPEG image bytes
     * @return The position where video data should start
     */
    private static int findVideoStartPos(byte[] imageBytes) {
        // Look for the end of image marker (0xFFD9) or just return the end of file
        // In motion photos, video is usually appended at the very end
        return imageBytes.length;
    }
    
    /**
     * Inserts XMP segment into JPEG at specified position
     * @param jpegBytes The original JPEG bytes
     * @param xmpSegment The XMP segment to insert
     * @param insertPosition The position to insert the XMP segment
     * @return Modified JPEG bytes with XMP inserted
     */
    private static byte[] insertXMPIntoJpeg(byte[] jpegBytes, byte[] xmpSegment, int insertPosition) throws IOException {
        // Adjust APP1 segment length in the JPEG if needed
        byte[] adjustedImageBytes = adjustApp1SegmentLength(jpegBytes, xmpSegment, insertPosition);
        
        // Create result array
        byte[] result = new byte[adjustedImageBytes.length + xmpSegment.length];
        
        // Copy bytes before insertion point
        System.arraycopy(adjustedImageBytes, 0, result, 0, insertPosition);
        
        // Copy XMP segment
        System.arraycopy(xmpSegment, 0, result, insertPosition, xmpSegment.length);
        
        // Copy bytes after insertion point
        System.arraycopy(adjustedImageBytes, insertPosition, result, insertPosition + xmpSegment.length, 
                         adjustedImageBytes.length - insertPosition);
        
        return result;
    }
    
    /**
     * Adjusts the length field in an APP1 segment header if we're inserting/replacing XMP
     * @param jpegBytes The original JPEG bytes
     * @param xmpSegment The new XMP segment
     * @param insertPosition The position where XMP will be inserted
     * @return Adjusted JPEG bytes with correct APP1 length
     */
    private static byte[] adjustApp1SegmentLength(byte[] jpegBytes, byte[] xmpSegment, int insertPosition) {
        // Check if we're replacing an existing APP1 segment or adding a new one
        // If insertPosition is at an existing APP1 marker, we need to adjust the length
        if (insertPosition + 1 < jpegBytes.length && 
            jpegBytes[insertPosition] == (byte) 0xFF && 
            jpegBytes[insertPosition + 1] == (byte) 0xE1) {
            // This is an existing APP1 segment, update its length
            int newLength = xmpSegment.length - 2; // exclude the 2-byte length field itself
            byte[] result = jpegBytes.clone();
            result[insertPosition + 2] = (byte) ((newLength >> 8) & 0xFF); // High byte
            result[insertPosition + 3] = (byte) (newLength & 0xFF);        // Low byte
            return result;
        }
        
        // If it's a new APP1 segment, no need to adjust existing lengths
        return jpegBytes;
    }
    
    /**
     * Inserts XMP metadata into a JPEG file (wrapper method)
     * @param jpegBytes The original JPEG bytes
     * @param videoSize The size of the embedded video in bytes
     * @return Modified JPEG bytes with XMP metadata inserted
     */
    private static byte[] insertXMPIntoJpeg(byte[] jpegBytes, int videoSize) throws IOException {
        // Generate XMP metadata
        String xmpMetadata = generateXMPMetadata(videoSize);
        byte[] xmpBytes = xmpMetadata.getBytes("UTF-8");
        
        // Create the APP1 XMP segment
        byte[] xmpSegment = createXmpApp1Segment(xmpBytes);
        
        // Find the position for APP1 marker (where XMP should go)
        // After SOI (0xFFD8) marker, before any image data
        int insertPosition = findXmpInsertPosition(jpegBytes);
        if (insertPosition == -1) {
            // If no position found, insert after SOI (position 2)
            insertPosition = 2;
        }
        
        // Insert the XMP segment into the JPEG
        return insertXMPIntoJpeg(jpegBytes, xmpSegment, insertPosition);
    }
    
    /**
     * Finds the appropriate insert position for XMP in a JPEG file
     * @param jpegBytes The JPEG file bytes
     * @return The position to insert the XMP segment, or -1 if not found
     */
    private static int findXmpInsertPosition(byte[] jpegBytes) {
        // JPEG structure: SOI (0xFFD8) + APP markers + image data
        // XMP typically goes in an APP1 marker (0xFFE1) or we insert a new one
        if (jpegBytes.length < 2) {
            return -1;
        }
        
        // Check for SOI marker at beginning (0xFFD8)
        if (jpegBytes[0] == (byte) 0xFF && jpegBytes[1] == (byte) 0xD8) {
            // Look for existing APP markers after SOI to insert our XMP
            // Start after SOI and look for next marker
            int pos = 2;
            
            while (pos < jpegBytes.length - 1) {
                if (jpegBytes[pos] == (byte) 0xFF) {
                    // Found marker byte
                    byte marker = jpegBytes[pos + 1];
                    if (marker != (byte) 0x00 && marker != (byte) 0xFF) {
                        // This is a valid marker
                        if (marker == (byte) 0xE1) {
                            // This is an existing APP1 marker (could be XMP or EXIF)
                            // We can either replace this or add after it
                            // Let's return the position of this existing APP1
                            return pos;
                        } else if (marker >= 0xE0 && marker <= 0xEF) {
                            // This is another APP marker (APP0, APP2, etc.), skip it
                            if (pos + 3 < jpegBytes.length) {
                                // Read the length of this APP segment
                                int segmentLength = ((jpegBytes[pos + 2] & 0xFF) << 8) | (jpegBytes[pos + 3] & 0xFF);
                                pos += 2 + segmentLength; // Skip marker and its data
                                continue;
                            }
                        } else if (marker >= 0xC0 && marker <= 0xDF) {
                            // This is a SOF (Start of Frame) or SOS (Start of Scan) or other image data marker
                            // This means we're at the image data, we should insert XMP before this
                            // But if we haven't found an APP1 yet, we need to insert a new one after SOI or other APP segments
                            return findPositionForNewApp1(jpegBytes, 2);
                        }
                    }
                }
                pos++;
            }
            
            // If we get here, we've reached the end without finding image data
            // Insert after SOI and any existing APP segments
            return findPositionForNewApp1(jpegBytes, 2);
        }
        
        // If no SOI found, return error
        return -1;
    }
    
    /**
     * Finds the best position to insert a new APP1 segment
     * @param jpegBytes The JPEG bytes
     * @param startPos Starting position to search from
     * @return Position to insert new APP1 segment
     */
    private static int findPositionForNewApp1(byte[] jpegBytes, int startPos) {
        int pos = startPos;
        
        // Skip SOI marker (2 bytes) if starting at 2
        if (pos == 2) pos = 4;
        
        while (pos < jpegBytes.length - 1) {
            if (jpegBytes[pos] == (byte) 0xFF) {
                byte marker = jpegBytes[pos + 1];
                if (marker != (byte) 0x00 && marker != (byte) 0xFF) {
                    // This is a valid marker
                    if (marker >= 0xE0 && marker <= 0xEF) {
                        // This is an APP marker, skip it
                        if (pos + 3 < jpegBytes.length) {
                            int segmentLength = ((jpegBytes[pos + 2] & 0xFF) << 8) | (jpegBytes[pos + 3] & 0xFF);
                            pos += 2 + segmentLength;
                            continue;
                        }
                    } else if (marker >= 0xC0 && marker <= 0xDF) {
                        // This is a SOF/SOS/DHT/DQT marker, meaning we're at image data
                        // Insert APP1 before this marker
                        return pos;
                    }
                }
            }
            pos++;
        }
        
        // If we get to the end, insert at current position
        return pos;
    }
    
    /**
     * Generates XMP metadata for live photo following Google's Motion Photo specification
     * @param videoSize The size of the embedded video in bytes
     * @return XMP metadata string
     */
    private static String generateXMPMetadata(int videoSize) {
        // Using Google's standard XMP format for Motion Photos
        // The MicroVideoOffset should be the size of the final file up to the video start
        // which is the original image size plus the XMP segment size
        return String.format(
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\"" +
            "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"" +
            "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"" +
            "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"" +
            "    xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"" +
            "  GCamera:MotionPhoto=\"1\"" +
            "  GCamera:MotionPhotoVersion=\"1\"" +
            "  GCamera:MotionPhotoPresentationTimestampUs=\"0\"" +
            "  OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\"" +
            "  OpCamera:MotionPhotoOwner=\"xhs\"" +
            "  OpCamera:OLivePhotoVersion=\"2\"" +
            "  OpCamera:VideoLength=\"%d\"" +
            "  GCamera:MicroVideoVersion=\"1\"" +
            "  GCamera:MicroVideo=\"1\"" +
            "  GCamera:MicroVideoOffset=\"%d\"" +
            "  GCamera:MicroVideoPresentationTimestampUs=\"0\">" +
            "  <Container:Directory>" +
            "    <rdf:Seq>" +
            "      <rdf:li rdf:parseType=\"Resource\">" +
            "        <Container:Item" +
            "          Item:Mime=\"image/jpeg\"" +
            "          Item:Semantic=\"Primary\"" +
            "          Item:Length=\"0\"/>" +
            "      </rdf:li>" +
            "      <rdf:li rdf:parseType=\"Resource\">" +
            "        <Container:Item" +
            "          Item:Mime=\"video/mp4\"" +
            "          Item:Semantic=\"MotionPhoto\"" +
            "          Item:Length=\"%d\"/>" +
            "      </rdf:li>" +
            "    </rdf:Seq>" +
            "  </Container:Directory>" +
            "</rdf:Description>" +
            "</rdf:RDF>" +
            "</x:xmpmeta>",
            videoSize, videoSize, videoSize
        );
    }
    
    /**
     * Creates an APP1 XMP segment with proper JPEG header
     * @param xmpData The XMP data as bytes
     * @return Byte array representing the APP1 XMP segment
     */
    private static byte[] createXmpApp1Segment(byte[] xmpData) throws IOException {
        // XMP header: "http://ns.adobe.com/xap/1.0/\0"
        byte[] xmpHeader = "http://ns.adobe.com/xap/1.0/\0".getBytes("UTF-8");
        
        // Calculate total segment length: xmp header + xmp data + 2 bytes for length field
        int segmentLengthWithoutLengthField = xmpHeader.length + xmpData.length;
        int totalSegmentLength = segmentLengthWithoutLengthField + 2; // +2 for the length field itself
        
        // Create the segment
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        
        // Write APP1 marker (0xFFE1)
        segment.write(0xFF);
        segment.write(0xE1);
        
        // Write length field (2 bytes, big-endian)
        segment.write((totalSegmentLength >> 8) & 0xFF);
        segment.write(totalSegmentLength & 0xFF);
        
        // Write XMP header
        segment.write(xmpHeader);
        
        // Write XMP data
        segment.write(xmpData);
        
        return segment.toByteArray();
    }
    
    /**
     * Reads a file into a byte array
     * @param file The file to read
     * @return Byte array containing the file contents
     * @throws IOException
     */
    private static byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            
            int nRead;
            byte[] data = new byte[16384]; // 16KB buffer
            
            while ((nRead = fis.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            
            return buffer.toByteArray();
        }
    }
}
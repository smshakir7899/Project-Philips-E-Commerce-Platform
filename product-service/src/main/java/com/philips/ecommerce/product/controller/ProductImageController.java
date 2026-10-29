package com.philips.ecommerce.product.controller;

import com.philips.ecommerce.common.dto.ApiErrorResponse;
import com.philips.ecommerce.product.dto.PresignedUrlResponse;
import com.philips.ecommerce.product.dto.ProductImageResponse;
import com.philips.ecommerce.product.service.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@Tag(name = "Product images", description = "Images stored in Amazon S3 (MinIO locally)")
@RestController
@RequestMapping("/api/products/{productId}/images")
public class ProductImageController {

    private final ProductImageService imageService;

    public ProductImageController(ProductImageService imageService) {
        this.imageService = imageService;
    }

    @Operation(summary = "Upload an image (multipart field 'file'; image/jpeg, image/png or image/webp; max 5MB)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stored"),
            @ApiResponse(responseCode = "400", description = "UNSUPPORTED_IMAGE_TYPE / EMPTY_FILE",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "STORAGE_UNAVAILABLE",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductImageResponse> upload(@PathVariable Long productId,
                                                       @RequestParam("file") MultipartFile file) {
        ProductImageResponse image = imageService.upload(productId, file);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{imageId}")
                .buildAndExpand(image.getId()).toUri();
        return ResponseEntity.created(location).body(image);
    }

    @Operation(summary = "Delete an image (row first, then the S3 object)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "IMAGE_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(@PathVariable Long productId, @PathVariable Long imageId) {
        imageService.delete(productId, imageId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Time-limited presigned GET URL for the image object")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned URL"),
            @ApiResponse(responseCode = "404", description = "IMAGE_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{imageId}/presigned-url")
    public PresignedUrlResponse presignedUrl(@PathVariable Long productId, @PathVariable Long imageId) {
        return imageService.presignedUrl(productId, imageId);
    }
}

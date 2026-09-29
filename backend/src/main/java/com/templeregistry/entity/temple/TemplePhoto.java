package com.templeregistry.entity.temple;

import com.templeregistry.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "temple_photos")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class TemplePhoto extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "temple_id", nullable = false)
    private Temple temple;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Builder.Default
    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary = false;

    @Column(name = "display_order")
    private Integer displayOrder;

    /**
     * Raw image bytes stored in the database.
     * NULL for photos uploaded before V103 (those are served from the local filesystem via filePath).
     * New uploads always populate this column so the image is accessible on every machine
     * that connects to the shared database.
     */
    @Lob
    @Column(name = "image_data", columnDefinition = "MEDIUMBLOB")
    private byte[] imageData;

    /** MIME type recorded at upload time (e.g. "image/jpeg"). Used when serving from DB. */
    @Column(name = "content_type", length = 100)
    private String contentType;
}
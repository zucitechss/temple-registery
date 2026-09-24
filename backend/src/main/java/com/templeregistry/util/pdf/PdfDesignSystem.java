package com.templeregistry.util.pdf;

import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralized PDF Design System for the Temple Registry application.
 *
 * <p>Provides a consistent government-grade color palette, typography,
 * and reusable layout components (headers, footers, tables, status badges)
 * for use across all PDF templates.
 *
 * <p>Design language: professional government document — navy/gold primary palette,
 * structured hierarchy, clean whitespace, accessible typography.
 */
public final class PdfDesignSystem {

    // ─── Color Palette ────────────────────────────────────────────────────────

    /** Primary dark navy — used for page headers and title bars. */
    public static final Color COLOR_PRIMARY       = new DeviceRgb(0x0D, 0x21, 0x37);
    /** Gold accent — used for decorative rules, section dividers, seal rings. */
    public static final Color COLOR_ACCENT        = new DeviceRgb(0xC4, 0x9A, 0x22);
    /** Steel blue — used for table column headers. */
    public static final Color COLOR_HEADER_BG     = new DeviceRgb(0x1A, 0x3A, 0x5C);
    /** Soft sky blue — alternate body section header. */
    public static final Color COLOR_SECTION_BG    = new DeviceRgb(0xE8, 0xF0, 0xFE);
    /** Very light grey — alternate table row stripe. */
    public static final Color COLOR_ROW_STRIPE    = new DeviceRgb(0xF5, 0xF7, 0xFA);
    /** White — primary cell background. */
    public static final Color COLOR_WHITE         = new DeviceRgb(0xFF, 0xFF, 0xFF);
    /** Light border grey — cell and section borders. */
    public static final Color COLOR_BORDER        = new DeviceRgb(0xCC, 0xD3, 0xDC);
    /** Dark text — primary body text. */
    public static final Color COLOR_TEXT_PRIMARY  = new DeviceRgb(0x1A, 0x1A, 0x2E);
    /** Medium grey — secondary / metadata text. */
    public static final Color COLOR_TEXT_MUTED    = new DeviceRgb(0x6B, 0x74, 0x80);

    // Status badge colors
    /** Green — APPROVED status badge. */
    public static final Color COLOR_APPROVED      = new DeviceRgb(0x0F, 0x76, 0x54);
    /** Amber — PENDING/SUBMITTED status badge. */
    public static final Color COLOR_PENDING       = new DeviceRgb(0xB4, 0x56, 0x09);
    /** Red — REJECTED status badge. */
    public static final Color COLOR_REJECTED      = new DeviceRgb(0xB9, 0x18, 0x18);
    /** Slate — DRAFT status badge. */
    public static final Color COLOR_DRAFT         = new DeviceRgb(0x4B, 0x55, 0x63);

    // ─── Font Sizes ───────────────────────────────────────────────────────────

    public static final float FONT_DISPLAY  = 22f;
    public static final float FONT_TITLE    = 16f;
    public static final float FONT_SUBTITLE = 12f;
    public static final float FONT_BODY     = 10f;
    public static final float FONT_SMALL    = 8.5f;
    public static final float FONT_TINY     = 7.5f;

    // ─── Spacing ──────────────────────────────────────────────────────────────

    public static final float PADDING_CELL     = 6f;
    public static final float PADDING_SECTION  = 12f;

    // ─── Date Formatters ──────────────────────────────────────────────────────

    public static final DateTimeFormatter DATE_FORMATTER     = DateTimeFormatter.ofPattern("dd MMM yyyy");
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'IST'");

    // ─── Private constructor ───────────────────────────────────────────────────

    private PdfDesignSystem() {}

    // ─── Font Helpers ─────────────────────────────────────────────────────────

    public static PdfFont fontRegular() {
        try {
            return PdfFontFactory.createFont(
                com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load PDF font", e);
        }
    }

    public static PdfFont fontBold() {
        try {
            return PdfFontFactory.createFont(
                com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load PDF font", e);
        }
    }

    public static PdfFont fontItalic() {
        try {
            return PdfFontFactory.createFont(
                com.itextpdf.io.font.constants.StandardFonts.HELVETICA_OBLIQUE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load PDF font", e);
        }
    }

    // ─── Document Header Block ────────────────────────────────────────────────

    /**
     * Renders the standard government-style page header block.
     *
     * @param document  iText Document
     * @param docTitle  Primary document title (e.g., "Temple Export Report")
     * @param subTitle  Optional subtitle / module name (may be null)
     * @param generatedBy  User/system that generated this document (may be null)
     */
    public static void addPageHeader(Document document, String docTitle, String subTitle, String generatedBy) {
        PdfFont bold = fontBold();
        PdfFont regular = fontRegular();

        // Top gold accent rule
        Div rule = new Div()
            .setHeight(4f)
            .setBackgroundColor(COLOR_ACCENT)
            .setMarginBottom(0f);
        document.add(rule);

        // Navy header band
        Table headerTable = new Table(UnitValue.createPercentArray(new float[]{70f, 30f}))
            .useAllAvailableWidth()
            .setBackgroundColor(COLOR_PRIMARY)
            .setMarginBottom(0f);

        // Left: system branding
        Cell leftCell = new Cell()
            .add(new Paragraph("Government of Karnataka")
                .setFont(regular).setFontSize(FONT_SMALL).setFontColor(COLOR_ACCENT)
                .setMarginBottom(1f))
            .add(new Paragraph("Temple Registry Management System")
                .setFont(bold).setFontSize(FONT_SUBTITLE).setFontColor(COLOR_WHITE)
                .setMarginBottom(0f))
            .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
            .setPadding(PADDING_SECTION);

        // Right: document type label
        Paragraph typeLabel = new Paragraph(docTitle.toUpperCase())
            .setFont(bold).setFontSize(FONT_SMALL)
            .setFontColor(COLOR_WHITE)
            .setTextAlignment(TextAlignment.RIGHT);

        Paragraph dateLabel = new Paragraph(
            "Generated: " + LocalDateTime.now().format(DATETIME_FORMATTER))
            .setFont(regular).setFontSize(FONT_TINY)
            .setFontColor(COLOR_ACCENT)
            .setTextAlignment(TextAlignment.RIGHT);

        Cell rightCell = new Cell()
            .add(typeLabel)
            .add(dateLabel)
            .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
            .setPadding(PADDING_SECTION)
            .setVerticalAlignment(VerticalAlignment.MIDDLE);

        headerTable.addCell(leftCell);
        headerTable.addCell(rightCell);
        document.add(headerTable);

        // Bottom gold accent rule
        Div rule2 = new Div()
            .setHeight(2f)
            .setBackgroundColor(COLOR_ACCENT)
            .setMarginBottom(PADDING_SECTION);
        document.add(rule2);

        // Document title block
        if (subTitle != null && !subTitle.isBlank()) {
            Paragraph titlePara = new Paragraph(subTitle)
                .setFont(bold)
                .setFontSize(FONT_TITLE)
                .setFontColor(COLOR_TEXT_PRIMARY)
                .setMarginBottom(2f);
            document.add(titlePara);
        }

        if (generatedBy != null && !generatedBy.isBlank()) {
            Paragraph genByPara = new Paragraph("Prepared by: " + generatedBy)
                .setFont(regular)
                .setFontSize(FONT_SMALL)
                .setFontColor(COLOR_TEXT_MUTED)
                .setMarginBottom(PADDING_SECTION);
            document.add(genByPara);
        }
    }

    // ─── Section Divider ──────────────────────────────────────────────────────

    public static void addSectionDivider(Document document, String sectionTitle) {
        PdfFont bold = fontBold();

        Table sectionHeader = new Table(1)
            .useAllAvailableWidth()
            .setBackgroundColor(COLOR_SECTION_BG)
            .setMarginTop(PADDING_SECTION)
            .setMarginBottom(6f)
            .setBorderTop(new SolidBorder(COLOR_ACCENT, 1.5f));

        Cell cell = new Cell()
            .add(new Paragraph(sectionTitle)
                .setFont(bold)
                .setFontSize(FONT_BODY)
                .setFontColor(COLOR_HEADER_BG))
            .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
            .setPaddingTop(5f).setPaddingBottom(5f)
            .setPaddingLeft(8f).setPaddingRight(8f);

        sectionHeader.addCell(cell);
        document.add(sectionHeader);
    }

    // ─── Key-Value Info Grid ──────────────────────────────────────────────────

    /**
     * Renders a 2-column label/value grid (4 columns: label, value, label, value).
     */
    public static Table buildInfoGrid(String[][] pairs) {
        PdfFont bold = fontBold();
        PdfFont regular = fontRegular();

        Table table = new Table(UnitValue.createPercentArray(new float[]{25f, 25f, 25f, 25f}))
            .useAllAvailableWidth()
            .setMarginBottom(8f);

        for (String[] pair : pairs) {
            String label1 = pair.length > 0 ? safeStr(pair[0]) : "";
            String value1 = pair.length > 1 ? safeStr(pair[1]) : "";
            String label2 = pair.length > 2 ? safeStr(pair[2]) : "";
            String value2 = pair.length > 3 ? safeStr(pair[3]) : "";

            table.addCell(labelCell(label1, bold));
            table.addCell(valueCell(value1, regular));
            if (!label2.isEmpty()) {
                table.addCell(labelCell(label2, bold));
                table.addCell(valueCell(value2, regular));
            } else {
                table.addCell(emptyCell());
                table.addCell(emptyCell());
            }
        }
        return table;
    }

    private static Cell labelCell(String text, PdfFont bold) {
        return new Cell()
            .add(new Paragraph(text)
                .setFont(bold)
                .setFontSize(FONT_SMALL)
                .setFontColor(COLOR_TEXT_MUTED))
            .setBackgroundColor(COLOR_ROW_STRIPE)
            .setBorder(new SolidBorder(COLOR_BORDER, 0.5f))
            .setPadding(PADDING_CELL);
    }

    private static Cell valueCell(String text, PdfFont regular) {
        return new Cell()
            .add(new Paragraph(text)
                .setFont(regular)
                .setFontSize(FONT_BODY)
                .setFontColor(COLOR_TEXT_PRIMARY))
            .setBorder(new SolidBorder(COLOR_BORDER, 0.5f))
            .setPadding(PADDING_CELL);
    }

    private static Cell emptyCell() {
        return new Cell().setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
    }

    // ─── Data Table ───────────────────────────────────────────────────────────

    /**
     * Builds a styled data table from rows.
     * rows[0] is treated as the header row.
     * Applies alternating row stripes and professional column header styling.
     */
    public static Table buildDataTable(java.util.List<String[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return new Table(1).addCell(new Cell()
                .add(new Paragraph("No data available.")
                    .setFont(fontItalic())
                    .setFontSize(FONT_BODY)
                    .setFontColor(COLOR_TEXT_MUTED)));
        }

        PdfFont bold = fontBold();
        PdfFont regular = fontRegular();

        String[] headers = rows.get(0);
        Table table = new Table(UnitValue.createPercentArray(equalWidths(headers.length)))
            .useAllAvailableWidth()
            .setMarginBottom(PADDING_SECTION);

        // ── Header row
        for (String header : headers) {
            table.addHeaderCell(
                new Cell()
                    .add(new Paragraph(header.toUpperCase())
                        .setFont(bold)
                        .setFontSize(FONT_SMALL)
                        .setFontColor(COLOR_WHITE))
                    .setBackgroundColor(COLOR_HEADER_BG)
                    .setBorder(new SolidBorder(COLOR_PRIMARY, 0.5f))
                    .setPadding(PADDING_CELL)
            );
        }

        // ── Data rows with stripe
        for (int i = 1; i < rows.size(); i++) {
            Color rowBg = (i % 2 == 0) ? COLOR_ROW_STRIPE : COLOR_WHITE;
            String[] rowData = rows.get(i);
            for (int c = 0; c < headers.length; c++) {
                String cellText = (c < rowData.length) ? safeStr(rowData[c]) : "";
                table.addCell(
                    new Cell()
                        .add(new Paragraph(cellText)
                            .setFont(regular)
                            .setFontSize(FONT_SMALL)
                            .setFontColor(COLOR_TEXT_PRIMARY))
                        .setBackgroundColor(rowBg)
                        .setBorder(new SolidBorder(COLOR_BORDER, 0.3f))
                        .setPadding(PADDING_CELL)
                );
            }
        }

        return table;
    }

    // ─── Status Badge ─────────────────────────────────────────────────────────

    public static Paragraph buildStatusBadge(String status) {
        PdfFont bold = fontBold();
        Color bgColor = statusColor(status);

        Text statusText = new Text("  " + status.toUpperCase() + "  ")
            .setFont(bold)
            .setFontSize(FONT_SMALL)
            .setFontColor(COLOR_WHITE)
            .setBackgroundColor(bgColor);

        return new Paragraph(statusText).setMarginTop(4f).setMarginBottom(4f);
    }

    private static Color statusColor(String status) {
        if (status == null) return COLOR_DRAFT;
        return switch (status.toUpperCase()) {
            case "APPROVED"  -> COLOR_APPROVED;
            case "REJECTED"  -> COLOR_REJECTED;
            case "SUBMITTED", "PENDING", "PENDING_REVIEW" -> COLOR_PENDING;
            default          -> COLOR_DRAFT;
        };
    }

    // ─── Page Footer (via Event Handler) ─────────────────────────────────────

    /**
     * Renders a consistent footer on the current page.
     *
     * @param canvas    PdfCanvas of the current page
     * @param pageSize  page size rectangle (from {@code PdfDocument.getDefaultPageSize()})
     * @param pageNo    Current page number (1-based)
     */
    public static void renderFooter(PdfCanvas canvas, Rectangle pageSize, int pageNo) {
        PdfFont regular;
        try {
            regular = PdfFontFactory.createFont(
                com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
        } catch (IOException e) {
            return;
        }
        float left = 36f;
        float bottom = 28f;
        float width = pageSize.getWidth() - 72f;

        // Thin gold rule above footer
        canvas.saveState()
            .setStrokeColor(COLOR_ACCENT)
            .setLineWidth(0.8f)
            .moveTo(left, bottom + 12f)
            .lineTo(left + width, bottom + 12f)
            .stroke()
            .restoreState();

        canvas.saveState()
            .beginText()
            .setFontAndSize(regular, FONT_TINY)
            .setFillColor(COLOR_TEXT_MUTED)
            .moveText(left, bottom)
            .showText("Temple Registry Management System — Government of Karnataka")
            .endText()
            .beginText()
            .setFontAndSize(regular, FONT_TINY)
            .setFillColor(COLOR_TEXT_MUTED)
            .moveText(left + width - 50f, bottom)
            .showText("Page " + pageNo)
            .endText()
            .beginText()
            .setFontAndSize(regular, FONT_TINY)
            .setFillColor(COLOR_TEXT_MUTED)
            .moveText(left + (width / 2f) - 60f, bottom)
            .showText("This is a system-generated document. No physical signature required.")
            .endText()
            .restoreState();
    }

    // ─── Watermark ────────────────────────────────────────────────────────────

    public static void addWatermark(PdfCanvas canvas, com.itextpdf.kernel.geom.Rectangle pageSize, String text) {
        PdfFont font;
        try {
            font = PdfFontFactory.createFont(
                com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD);
        } catch (IOException e) {
            return;
        }
        float x = pageSize.getWidth() / 2f;
        float y = pageSize.getHeight() / 2f;

        canvas.saveState()
            .setFillColor(new DeviceRgb(0xCC, 0xD3, 0xDC))
            .beginText()
            .setFontAndSize(font, 52f)
            .moveText(x - 100f, y)
            .showText(text)
            .endText()
            .restoreState();
    }

    // ─── Summary Stats Row ────────────────────────────────────────────────────

    public static void addSummaryBar(Document document, String[] labels, String[] values) {
        PdfFont bold = fontBold();
        PdfFont regular = fontRegular();

        int count = Math.min(labels.length, values.length);
        float[] widths = equalWidths(count);

        Table table = new Table(UnitValue.createPercentArray(widths))
            .useAllAvailableWidth()
            .setBackgroundColor(COLOR_SECTION_BG)
            .setMarginBottom(PADDING_SECTION)
            .setBorder(new SolidBorder(COLOR_BORDER, 0.5f));

        for (int i = 0; i < count; i++) {
            Cell cell = new Cell()
                .add(new Paragraph(values[i])
                    .setFont(bold).setFontSize(FONT_SUBTITLE).setFontColor(COLOR_HEADER_BG)
                    .setTextAlignment(TextAlignment.CENTER))
                .add(new Paragraph(labels[i])
                    .setFont(regular).setFontSize(FONT_TINY).setFontColor(COLOR_TEXT_MUTED)
                    .setTextAlignment(TextAlignment.CENTER))
                .setBorder(new SolidBorder(COLOR_BORDER, 0.5f))
                .setPadding(PADDING_SECTION);
            table.addCell(cell);
        }
        document.add(table);
    }

    // ─── Horizontal Rule ──────────────────────────────────────────────────────

    public static void addHorizontalRule(Document document) {
        Div line = new Div()
            .setHeight(1f)
            .setBackgroundColor(COLOR_BORDER)
            .setMarginTop(8f)
            .setMarginBottom(8f);
        document.add(line);
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    public static String safeStr(Object value) {
        if (value == null) return "—";
        String s = value.toString().trim();
        return s.isEmpty() ? "—" : s;
    }

    public static String formatStatus(String status) {
        if (status == null || status.isBlank()) return "—";
        return switch (status.toUpperCase()) {
            case "APPROVED"         -> "Approved";
            case "REJECTED"         -> "Rejected";
            case "SUBMITTED"        -> "Submitted";
            case "PENDING_REVIEW"   -> "Pending Review";
            case "DRAFT"            -> "Draft";
            default                 -> status;
        };
    }

    private static float[] equalWidths(int count) {
        float[] widths = new float[count];
        float w = 100f / count;
        for (int i = 0; i < count; i++) widths[i] = w;
        return widths;
    }
}

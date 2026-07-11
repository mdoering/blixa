-- Reference PDF hosting (Task 2): one PDF file per reference, stored on disk (coldp.pdf.dir) and
-- served publicly at coldp.pdf.base-url + "/" + pdf (see name/PdfService, name/PdfController). This
-- is a filename, not the reference's own citable link -- `link` stays whatever the user set (see
-- ReferenceMapper.updatePdf's narrow CAS write, which never touches `link`).
ALTER TABLE reference ADD COLUMN pdf TEXT;

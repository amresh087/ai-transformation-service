package com.retail.ai.utilty;

import java.util.List;

import com.retail.ai.edi.EdiSegment;
import com.retail.ai.edi.MappingChunk;

public class PromptHelper {



  public static String buildPromptSegmentClassifier(String mappingChunk, String segmentXml) {
        return "Use the following XSLT mapping chunk to generate only the IDOC XML fragment for this EDI segment. "
                + "Do not echo the mapping chunk, do not return the XSLT mapping chunk, do not return any XML declaration, "
                + "and do not wrap the result in any additional documents or markdown fences. "
                + "Return only valid XML element content suitable for insertion inside an ORDERS05/IDOC document. "
                + "If you are unsure, return <unmapped/>.\n\n"
                + "Mapping chunk:\n" + mappingChunk + "\n\n"
                + "EDI segment XML:\n" + segmentXml;
    }

   public static String buildPromptIDocMapping(
            EdiSegment segment,
            List<MappingChunk> chunks,
            String previousIdocXml) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an SAP IDoc Mapping Engine.

                Your job is to EXECUTE the mapping described by the retrieved XSLT and
                produce the FINAL, WELL-FORMED SAP IDoc XML for an ORDERS05 message.

                You are NOT an XSLT generator. Never output XSLT syntax.

                ==================================================
                XML WELL-FORMEDNESS (HARD RULE)
                ==================================================

                Every tag you open MUST be closed by the SAME tag name, in the
                correct order. This is invalid and will be rejected:

                    <PARVW></PARTN></PARVW>

                This is valid:

                    <PARVW>BY</PARVW>
                    <PARTN>BUYERID</PARTN>

                Never nest a closing tag of one element inside another element's
                open/close pair. Never leave a tag open. Every element you emit
                (E1EDK01, E1EDKA1, E1EDP01, E1EDP19, E1EDP20, E1EDPT1, EDI_DC40)
                MUST be closed before the next sibling or parent tag opens. Before
                you finish, mentally verify every open tag has a matching close
                tag with the identical name, in the identical case, in the
                correct nesting order, and that no field (e.g. POSEX, MENGE,
                NETWR) appears as a direct child of <IDOC> or floating outside
                any wrapper element.

                Every tag must contain the ACTUAL VALUE extracted from the current
                EDI segment's field content. Do NOT emit empty tags like
                <BELNR></BELNR> unless the source segment genuinely has no value
                for that field. If a value exists in the EDI segment, it MUST
                appear as text content, never as a placeholder, never omitted.

                NEVER output an element whose name is an EDI segment or field
                wrapper name. The following must NEVER appear as output tags,
                under any circumstance: <UNB>, <UNH>, <UNT>, <UNZ>, <NAD>,
                <DTM>, <LIN>, <PIA>, <IMD>, <QTY>, <PRI>, <CUX>, <BGM>,
                <SENDERID>, <RECEIVERID>, <segment>, <field>. These are EDI
                source names, not IDoc target names. Every value from these
                segments must be translated into the correct IDoc field per
                the reference table below.

                ==================================================
                DO NOT COPY VALUES FROM THE GOLDEN EXAMPLE (HARD RULE)
                ==================================================

                The GOLDEN EXAMPLE below (qualifiers "SE"/"BY", item IDs
                "ITEM1001A"/"ITEM1002", dates, prices, etc.) is FICTIONAL
                sample data used ONLY to teach you the output PATTERN and
                STRUCTURE. It is not real input.

                Every value in your actual output MUST come from the
                "CURRENT EDI SEGMENT" and "CURRENT IDOC" sections below —
                never from the golden example. In particular: copy party
                qualifiers (PARVW) EXACTLY as they appear in the real NAD
                segment (e.g. if the source says "SE", output "SE" — do not
                "correct" it to "SU" because the example used "SU").

                ==================================================
                EDIFACT -> IDOC FIELD REFERENCE
                ==================================================

                Use this reference to decide where each EDI value goes. Field
                positions are colon-separated within each <field> element.

                UNB, interchange header
                    format "UNOC:3", "SENDERID:ZZ", "RECEIVERID:ZZ",
                    "YYMMDD:HHMM", control-ref, "ORDERS"
                    -> THIS segment, not DTM, is the source for:
                         EDI_DC40/CREDAT = "20" + the YYMMDD field (century-
                           expanded to YYYY), e.g. "200722" -> "20200722"
                         EDI_DC40/CRETIM = the HHMM field zero-padded to 6
                           digits, e.g. "1500" -> "150000"
                         EDI_DC40/SNDPRN = text before the first colon in
                           the SENDERID field, e.g. "SENDERID:ZZ" -> "SENDERID"
                         EDI_DC40/RCVPRN = text before the first colon in
                           the RECEIVERID field, e.g. "RECEIVERID:ZZ" -> "RECEIVERID"
                    NEVER take CREDAT from DTM. DTM is a business document
                    date and goes to E1EDK01/DATUM only. CREDAT/CRETIM are an
                    atomic pair -- both must come from UNB, never one from
                    UNB and the other from a different segment.
                    EDI_DC40 LOCK: once EDI_DC40/CREDAT, CRETIM, SNDPRN, and
                    RCVPRN have been populated (visible as non-empty in the
                    CURRENT IDOC below), they are immutable. Never recompute,
                    replace, or "correct" them on a later call, no matter
                    what the current segment is.

                DTM, qualifier 137 (document/message date)
                    format is "137:YYYYMMDD:102"
                    -> E1EDK01/DATUM = the YYYYMMDD value ONLY. Never use this
                       value for EDI_DC40/CREDAT.

                NAD, party segment
                    field 1 = party qualifier (BY = buyer, SE = supplier)
                    field 2 = optional internal id, field 3 = party identifier
                    -> creates ONE E1EDKA1 segment per distinct NAD:
                         E1EDKA1/PARVW = the qualifier, copied verbatim from
                           field 1 of the CURRENT segment (do not normalize
                           or substitute a different qualifier code)
                         E1EDKA1/PARTN = the party identifier: the text
                           BEFORE the FIRST colon in field 3, e.g.
                           "BUYERID::92" -> "BUYERID", "SUPPLIERID::92" -> "SUPPLIERID"
                    -> never merge two different NAD qualifiers into one E1EDKA1
                    -> EVERY NAD segment present in the input MUST produce its
                       own E1EDKA1. If the input has a BY and a SE/SU, the
                       output MUST have both E1EDKA1 segments. Dropping one is
                       a critical error -- before finishing, count the NAD
                       segments in the input and confirm the same count of
                       E1EDKA1 segments exists in the output.

                CUX, currency
                    format "2:EUR:4" -> field 2 is the ISO currency code
                    -> E1EDK01/WAERS = that code

                LIN, line item
                    LIN may arrive in either of two shapes -- handle both:
                      (a) ONE line item per LIN segment: field 1 = the line
                          number, field 2 = "code:qualifier" for that single
                          item (e.g. field1="1", field2="ITEM1001:IN").
                      (b) MULTIPLE items concatenated in one segment as
                          repeating (line_number, "code:qualifier") pairs
                          (e.g. field1="1", field2="ITEM1001A:SA", field3="2",
                          field4="ITEM1002:IN"). Split positionally: the 1st
                          pair is line item 1, the 2nd pair is line item 2, etc.
                    In both shapes, the line number is taken LITERALLY from
                    the LIN field -- it is the ACTUAL line item number from
                    the source data (e.g. "1", "2", "7", whatever it literally
                    is). E1EDP01/POSEX MUST equal this value verbatim.
                    NEVER invent, renumber, increment, or reorder POSEX. It is
                    NOT a running count of how many E1EDP01 segments you have
                    created so far -- it is a direct copy of the literal LIN
                    line-number field for that item.
                    Qualifier meanings:
                         SA = buyer's item number
                         IN = (international/buyer) item number used alone when
                              no SA is present for that item
                         VN = vendor/supplier item number
                    -> each distinct line number creates ONE E1EDP01 segment:
                         E1EDP01/POSEX = the line number
                         each code:qualifier pair for that line becomes a nested
                           E1EDP19 with QUALF = mapped qualifier code below and
                           IDNLF = the item code:
                              SA -> QUALF 002
                              IN -> QUALF 001
                              VN -> QUALF 001 (only if IN absent for that line)

                PIA, additional item identification
                    format field 1 = sequence/qualifier number (usually "1"),
                    field 2 = "code:qualifier" (e.g. "ITEM1001A:SA")
                    PIA always refers to the SAME line item as the LIN segment
                    it is paired with in segment order (i.e. the CURRENT/most
                    recent line item being built -- the most recently opened
                    E1EDP01 that does not yet have an E1EDP19 for this qualifier).
                    -> adds a nested E1EDP19 to that E1EDP01, using the SAME
                       qualifier mapping as LIN:
                            SA -> QUALF 002, IDNLF = the item code
                            VN -> QUALF 001 (only if that line has no IN/LIN
                                  value already mapped to QUALF 001)
                    Do NOT create a new E1EDP01 for a PIA segment. Do NOT
                    attach it to any line item other than the one it
                    immediately follows in segment order.

                IMD, free text description
                    format "F::WIDGET" -> the text AFTER the LAST colon in
                    field 2 is the description text, e.g. ":::WIDGET" -> "WIDGET"
                    -> nested E1EDPT1/TDLINE on the CURRENT/most recent line
                       item being built.
                    SCOPE RULE: an IMD applies ONLY to the single line item it
                    immediately follows in segment order. Do NOT copy or carry
                    an IMD/TDLINE value forward onto any later line item that
                    has no IMD of its own. Example: if IMD appears between
                    LIN 1 and LIN 2, only E1EDP01[POSEX=1] gets an E1EDPT1 --
                    E1EDP01[POSEX=2] gets none, even though it is built later
                    in the same call.

                QTY, quantity
                    QTY may list MULTIPLE quantities concatenated, one per line
                    item, in the SAME ORDER as the LIN pairs were listed, OR
                    arrive as a single quantity immediately following one LIN
                    segment. Each entry is "qualifier:amount:unit".
                    -> for the Nth quantity entry, apply to line item N (or,
                       if only one entry, to the CURRENT/most recent line item):
                         E1EDP01/MENGE = amount
                         E1EDP01/MENEE = unit
                    (qualifier code itself, e.g. 21 vs 22, does not change the
                    IDoc target field -- both map to MENGE/MENEE for their
                    respective line item)

                PRI, price
                    format "AAA:20.00" -> field 2 is the net price
                    -> nested E1EDP20/NETWR on the CURRENT/most recent line item
                       being built. If multiple PRI values are present, apply
                       positionally to line items in the same order as LIN.
                    NETWR MUST be wrapped inside its own <E1EDP20> element,
                    which is itself nested inside the <E1EDP01>. This is
                    WRONG:
                        <E1EDP01><NETWR>15.50</NETWR></E1EDP01>
                        <PRI><NETWR>15.50</NETWR></PRI>
                    This is CORRECT:
                        <E1EDP01><E1EDP20><NETWR>15.50</NETWR></E1EDP20></E1EDP01>
                    NETWR must never appear as a direct child of E1EDP01, and
                    never inside a <PRI> tag (PRI is an EDI name, not an IDoc
                    name, and must never appear in the output at all).

                UNH/UNT (envelope/control segments)
                    -> never map directly and never appear in the output.

                UNZ (interchange trailer)
                    -> the UNZ interchange reference number becomes
                       EDI_DC40/DOCNUM (zero-padded control number, e.g.
                       "1" -> "00000000000001"), and only on the segment
                       that finalizes the document. UNZ itself is never
                       output as a tag.

                ==================================================
                LINE ITEM CORRELATION RULE
                ==================================================

                LIN, PIA, IMD, QTY, and PRI segments describing the same order
                will usually arrive together or across a few calls. Track
                which E1EDP01 (by POSEX) is "current" as you process segments
                in order, and attach PIA/IMD/QTY/PRI values to the correct
                POSEX using the positional and scope rules above. Never
                invent a new E1EDP01 for a POSEX that already exists in the
                CURRENT IDOC -- append nested children to the existing one
                instead, matched by its existing POSEX value.

                ==================================================
                GOLDEN EXAMPLE (follow this STRUCTURE exactly -- values shown
                are illustrative only; see "DO NOT COPY VALUES" rule above)
                ==================================================

                Given these EDI segments:

                    DTM: 137:20260722:102
                    NAD: BY, 92, BUYERID::92
                    NAD: SE, 92, SUPPLIERID::92
                    CUX: 2:EUR:4
                    LIN: 1, ITEM1001A:SA, 2, ITEM1002:IN
                    IMD: F, ::WIDGET
                    QTY: 22:100:PCE, 21:50:PCE
                    PRI: AAA:15.50, AAA:20.00

                The correct resulting IDoc is:

                <?xml version="1.0" encoding="UTF-8"?>
                <ORDERS05>
                  <IDOC BEGIN="1">
                    <EDI_DC40>
                      <TABNAM>EDI_DC40</TABNAM>
                      <DOCNUM>00000000000001</DOCNUM>
                      <SNDPRN>SENDERID</SNDPRN>
                      <RCVPRN>RECEIVERID</RCVPRN>
                      <CREDAT>20260722</CREDAT>
                      <CRETIM>150000</CRETIM>
                    </EDI_DC40>
                    <E1EDK01>
                      <BELNR>PO12345</BELNR>
                      <DATUM>20260722</DATUM>
                      <WAERS>EUR</WAERS>
                    </E1EDK01>
                    <E1EDKA1>
                      <PARVW>BY</PARVW>
                      <PARTN>BUYERID</PARTN>
                    </E1EDKA1>
                    <E1EDKA1>
                      <PARVW>SU</PARVW>
                      <PARTN>SUPPLIERID</PARTN>
                    </E1EDKA1>
                    <E1EDP01>
                      <POSEX>1</POSEX>
                      <MENGE>100</MENGE>
                      <MENEE>PCE</MENEE>
                      <E1EDP19>
                        <QUALF>002</QUALF>
                        <IDNLF>ITEM1001A</IDNLF>
                      </E1EDP19>
                      <E1EDP20>
                        <NETWR>15.50</NETWR>
                      </E1EDP20>
                      <E1EDPT1>
                        <TDLINE>WIDGET</TDLINE>
                      </E1EDPT1>
                    </E1EDP01>
                    <E1EDP01>
                      <POSEX>2</POSEX>
                      <MENGE>50</MENGE>
                      <MENEE>PCE</MENEE>
                      <E1EDP19>
                        <QUALF>001</QUALF>
                        <IDNLF>ITEM1002</IDNLF>
                      </E1EDP19>
                      <E1EDP20>
                        <NETWR>20.00</NETWR>
                      </E1EDP20>
                    </E1EDP01>
                  </IDOC>
                </ORDERS05>

                Notice: no empty tags, no broken nesting, every value traced back
                to a real field in the source segments (not copied from this
                example), and IMD/QTY/PRI/PIA values distributed positionally
                and by scope across the two E1EDP01 line items.

                ==================================================
                YOUR TASK
                ==================================================

                Update the existing SAP IDoc using the reference and example
                above. Map ONLY the current EDI segment shown below.

                Preserve all previously generated IDoc XML exactly as-is.
                Never modify or delete previous values. Never duplicate an
                existing value. If the current EDI segment belongs inside an
                existing IDoc segment (e.g. another NAD, another line item's
                QTY/PIA), append/attach the values per the rules above. If it
                starts a new repeating structure not yet present (E1EDP01,
                E1EDKA1, etc.), create a NEW SAP segment.

                ==================================================
                CURRENT IDOC
                ==================================================

                """);

        sb.append(previousIdocXml).append("\n\n");

        sb.append("""
                ==================================================
                CURRENT EDI SEGMENT
                ==================================================

                """);

        sb.append(segment.getRawXml()).append("\n\n");

        sb.append("""
                ==================================================
                RETRIEVED MAPPING CHUNKS (reference only -- do not copy or
                output any XSLT syntax from these)
                ==================================================

                """);

        int i = 1;

        for (MappingChunk chunk : chunks) {

            sb.append("Chunk ")
                    .append(i++)
                    .append(" (score=")
                    .append(chunk.getScore())
                    .append(")\n");

            sb.append(chunk.getChunkText());

            sb.append("\n\n");
        }

        sb.append("""
                ==================================================
                OUTPUT REQUIREMENTS
                ==================================================

                Return ONLY the COMPLETE updated SAP IDoc XML, well-formed per
                the rules above, following the GOLDEN EXAMPLE structure (not
                its values).

                Never output XSLT (<xsl:*> of any kind).
                Never output EDI segment or field wrappers (<segment>, <field>,
                <UNB>, <UNH>, <UNT>, <UNZ>, <NAD>, <DTM>, <LIN>, <PIA>, <IMD>,
                <QTY>, <PRI>, <CUX>, <BGM>, <SENDERID>, <RECEIVERID>).
                Never output an empty tag when the source has a value.
                Never break tag nesting. Never leave a field (POSEX, MENGE,
                NETWR, etc.) floating outside its required wrapper element.

                BEFORE YOU RETURN YOUR ANSWER, silently verify all of the
                following against your own draft, and fix anything that fails:
                  1. Every opened tag has a matching close tag, same name,
                     same case, correctly nested -- no exceptions.
                  2. No EDI segment/field name (see list above) appears as an
                     output tag anywhere in the document.
                  3. EDI_DC40/CREDAT and CRETIM were NOT taken from DTM, and
                     were NOT changed if they already had values in the
                     CURRENT IDOC.
                  4. Every E1EDKA1/PARTN value has had everything from the
                     first colon onward stripped off (no "::92" suffixes
                     remain anywhere in the output).
                  5. Every E1EDP01/POSEX is a literal copy of a real LIN line
                     number from the EDI input -- not a count, not an
                     increment, not invented.
                  6. Every NETWR sits inside E1EDP20 inside E1EDP01 -- never
                     directly inside E1EDP01, never inside a <PRI> tag.
                  7. No value in your output matches a GOLDEN EXAMPLE value
                     (SU, ITEM1001A, ITEM1002, 15.50, 20.00, 20260722, etc.)
                     unless that exact value is also independently present in
                     the actual CURRENT EDI SEGMENT or CURRENT IDOC below.

                No explanation. No markdown. No comments. No XML declaration
                repeated more than once.
                """);

        return sb.toString();
    }





// NOTE: adjust package/imports to match your actual project structure.
// Add this method alongside your existing buildPromptIDocMapping(...) in
// PromptHelper. It's the same rules/golden-example content, but the
// "CURRENT EDI SEGMENT" section becomes "CURRENT EDI SEGMENTS" (plural,
// a whole batch), and the task/output instructions are updated to tell
// the model to map every segment in the batch in one pass, respecting
// segment ORDER for correlation (LIN -> PIA/IMD/QTY/PRI).



       public static String buildPromptIDocMappingBatch(
            List<EdiSegment> segments,
            List<MappingChunk> chunks,
            String previousIdocXml) {
 
        StringBuilder sb = new StringBuilder();
 
        sb.append("""
                You are an SAP IDoc Mapping Engine.
 
                Your job is to EXECUTE the mapping described by the retrieved XSLT and
                produce the FINAL, WELL-FORMED SAP IDoc XML for an ORDERS05 message.
 
                You are NOT an XSLT generator. Never output XSLT syntax.
 
                ==================================================
                REQUIRED ENVELOPE SKELETON (HARD RULE -- CHECK THIS FIRST)
                ==================================================
 
                Every single response, with NO exceptions, MUST start with
                exactly this skeleton (values inside EDI_DC40/E1EDK01 vary,
                the STRUCTURE below does not):
 
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ORDERS05>
                      <IDOC BEGIN="1">
                        <EDI_DC40>
                          <TABNAM>EDI_DC40</TABNAM>
                          ...
                        </EDI_DC40>
                        ...
                      </IDOC>
                    </ORDERS05>
 
                Two things are dropped constantly and must NOT be:
                  - <IDOC BEGIN="1"> -- the BEGIN="1" attribute is
                    MANDATORY on every response, including updates to an
                    already-existing IDOC. <IDOC> with no attribute is
                    WRONG even if the CURRENT IDOC you were given also
                    lacked it -- add it.
                  - <TABNAM>EDI_DC40</TABNAM> -- MANDATORY as the first
                    child of <EDI_DC40>, literal text "EDI_DC40", every
                    single time.
 
                ==================================================
                XML WELL-FORMEDNESS (HARD RULE)
                ==================================================
 
                Every tag you open MUST be closed by the SAME tag name, in the
                correct order. This is invalid and will be rejected:
 
                    <PARVW></PARTN></PARVW>
 
                This is valid:
 
                    <PARVW>BY</PARVW>
                    <PARTN>BUYERID</PARTN>
 
                Never nest a closing tag of one element inside another element's
                open/close pair. Never leave a tag open. Every element you emit
                (E1EDK01, E1EDKA1, E1EDP01, E1EDP19, E1EDP20, E1EDPT1, EDI_DC40)
                MUST be closed before the next sibling or parent tag opens. Before
                you finish, mentally verify every open tag has a matching close
                tag with the identical name, in the identical case, in the
                correct nesting order, and that no field (e.g. POSEX, MENGE,
                NETWR) appears as a direct child of <IDOC> or floating outside
                any wrapper element.
 
                Every tag must contain the ACTUAL VALUE extracted from the current
                EDI segments' field content. Do NOT emit empty tags like
                <BELNR></BELNR> unless the source segment genuinely has no value
                for that field. If a value exists in an EDI segment, it MUST
                appear as text content, never as a placeholder, never omitted.
 
                NEVER output an element whose name is an EDI segment or field
                wrapper name. The following must NEVER appear as output tags,
                under any circumstance: <UNB>, <UNH>, <UNT>, <UNZ>, <NAD>,
                <DTM>, <LIN>, <PIA>, <IMD>, <QTY>, <PRI>, <CUX>, <BGM>,
                <SENDERID>, <RECEIVERID>, <segment>, <field>. These are EDI
                source names, not IDoc target names. Every value from these
                segments must be translated into the correct IDoc field per
                the reference table below.
 
                ==================================================
                DO NOT COPY VALUES FROM THE GOLDEN EXAMPLE (HARD RULE)
                ==================================================
 
                The GOLDEN EXAMPLE below (qualifiers "SE"/"BY", item IDs
                "ITEM1001A"/"ITEM1002", dates, prices, etc.) is FICTIONAL
                sample data used ONLY to teach you the output PATTERN and
                STRUCTURE. It is not real input.
 
                Every value in your actual output MUST come from the
                "CURRENT EDI SEGMENTS" and "CURRENT IDOC" sections below --
                never from the golden example. In particular: copy party
                qualifiers (PARVW) EXACTLY as they appear in the real NAD
                segment (e.g. if the source says "SE", output "SE" -- do not
                "correct" it to "SU" because the example used "SU").
 
                ==================================================
                EDIFACT -> IDOC FIELD REFERENCE
                ==================================================
 
                Use this reference to decide where each EDI value goes. Field
                positions are colon-separated within each <field> element.
 
                UNB, interchange header
                    format "UNOC:3", "SENDERID:ZZ", "RECEIVERID:ZZ",
                    "YYMMDD:HHMM", control-ref, "ORDERS"
                    -> THIS segment, not DTM, is the source for:
                         EDI_DC40/CREDAT = "20" + the YYMMDD field (century-
                           expanded to YYYY), e.g. "200722" -> "20200722"
                         EDI_DC40/CRETIM = the HHMM field zero-padded to 6
                           digits, e.g. "1500" -> "150000"
                         EDI_DC40/SNDPRN = text before the first colon in
                           the SENDERID field, e.g. "SENDERID:ZZ" -> "SENDERID"
                         EDI_DC40/RCVPRN = text before the first colon in
                           the RECEIVERID field, e.g. "RECEIVERID:ZZ" -> "RECEIVERID"
                    NEVER take CREDAT from DTM. DTM is a business document
                    date and goes to E1EDK01/DATUM only. CREDAT/CRETIM are an
                    atomic pair -- both must come from UNB, never one from
                    UNB and the other from a different segment.
                    EDI_DC40 LOCK: once EDI_DC40/CREDAT, CRETIM, SNDPRN, and
                    RCVPRN have been populated (visible as non-empty in the
                    CURRENT IDOC below), they are immutable. Never recompute,
                    replace, or "correct" them on a later call, no matter
                    what the current segments are.
 
                DTM, qualifier 137 (document/message date)
                    format is "137:YYYYMMDD:102"
                    -> E1EDK01/DATUM = the YYYYMMDD value ONLY. Never use this
                       value for EDI_DC40/CREDAT.
 
                NAD, party segment
                    field 1 = party qualifier (BY = buyer, SE = supplier)
                    field 2 = optional internal id, field 3 = party identifier
                    -> creates ONE E1EDKA1 segment per distinct NAD:
                         E1EDKA1/PARVW = the qualifier, copied verbatim from
                           field 1 of that NAD segment (do not normalize
                           or substitute a different qualifier code)
                         E1EDKA1/PARTN = the party identifier: the text
                           BEFORE the FIRST colon in field 3, e.g.
                           "BUYERID::92" -> "BUYERID", "SUPPLIERID::92" -> "SUPPLIERID"
                    -> never merge two different NAD qualifiers into one E1EDKA1
                    -> EVERY NAD segment present in the batch MUST produce its
                       own E1EDKA1. If the batch has a BY and a SE/SU, the
                       output MUST have both E1EDKA1 segments. Dropping one is
                       a critical error -- before finishing, count the NAD
                       segments in the batch and confirm the same count of
                       NEW E1EDKA1 segments has been added to the output
                       (on top of any that already existed in CURRENT IDOC).
 
                CUX, currency
                    format "2:EUR:4" -> field 2 is the ISO currency code
                    -> E1EDK01/WAERS = that code
 
                LIN, line item
                    LIN may arrive in either of two shapes -- handle both:
                      (a) ONE line item per LIN segment: field 1 = the line
                          number, field 2 = "code:qualifier" for that single
                          item (e.g. field1="1", field2="ITEM1001:IN").
                      (b) MULTIPLE items concatenated in one segment as
                          repeating (line_number, "code:qualifier") pairs
                          (e.g. field1="1", field2="ITEM1001A:SA", field3="2",
                          field4="ITEM1002:IN"). Split positionally: the 1st
                          pair is line item 1, the 2nd pair is line item 2, etc.
                    In both shapes, the line number is taken LITERALLY from
                    the LIN field -- it is the ACTUAL line item number from
                    the source data (e.g. "1", "2", "7", whatever it literally
                    is). E1EDP01/POSEX MUST equal this value verbatim.
                    NEVER invent, renumber, increment, or reorder POSEX. It is
                    NOT a running count of how many E1EDP01 segments you have
                    created so far -- it is a direct copy of the literal LIN
                    line-number field for that item.
                    Qualifier meanings:
                         SA = buyer's item number
                         IN = (international/buyer) item number used alone when
                              no SA is present for that item
                         VN = vendor/supplier item number
                    -> each distinct line number creates ONE E1EDP01 segment:
                         E1EDP01/POSEX = the line number
                         each code:qualifier pair for that line becomes a nested
                           E1EDP19 with QUALF = mapped qualifier code below and
                           IDNLF = the item code:
                              SA -> QUALF 002
                              IN -> QUALF 001
                              VN -> QUALF 001 (only if IN absent for that line)
                    E1EDP19 LOCK (HARD RULE): every E1EDP01 that was created
                    from a LIN segment MUST end up with AT LEAST ONE nested
                    E1EDP19 containing the item code from that LIN. An
                    E1EDP01 with POSEX/MENGE/MENEE/E1EDP20 but no E1EDP19 is
                    ALWAYS a critical error -- the item code was dropped.
                    Before returning your answer, check every E1EDP01 in
                    your output and confirm it has at least one E1EDP19
                    child if its source LIN provided an item code.
 
                PIA, additional item identification
                    format field 1 = sequence/qualifier number (usually "1"),
                    field 2 = "code:qualifier" (e.g. "ITEM1001A:SA")
                    PIA always refers to the SAME line item as the LIN segment
                    that PRECEDES it in segment ORDER within this batch (or,
                    if the batch has no LIN before it, the most recently
                    opened E1EDP01 that does not yet have an E1EDP19 for this
                    qualifier -- which may already exist in CURRENT IDOC from
                    a previous batch).
                    -> adds a nested E1EDP19 to that E1EDP01, using the SAME
                       qualifier mapping as LIN:
                            SA -> QUALF 002, IDNLF = the item code
                            VN -> QUALF 001 (only if that line has no IN/LIN
                                  value already mapped to QUALF 001)
                    Do NOT create a new E1EDP01 for a PIA segment. Do NOT
                    attach it to any line item other than the one it
                    immediately follows in segment order.
 
                IMD, free text description
                    format "F::WIDGET" -> the text AFTER the LAST colon in
                    field 2 is the description text, e.g. ":::WIDGET" -> "WIDGET"
                    -> nested E1EDPT1/TDLINE on the line item it immediately
                       follows in segment order within this batch.
                    SCOPE RULE: an IMD applies ONLY to the single line item it
                    immediately follows in segment order. Do NOT copy or carry
                    an IMD/TDLINE value forward onto any later line item that
                    has no IMD of its own. Example: if IMD appears between
                    LIN 1 and LIN 2, only E1EDP01[POSEX=1] gets an E1EDPT1 --
                    E1EDP01[POSEX=2] gets none, even though both are built in
                    the same batch.
 
                QTY, quantity
                    QTY may list MULTIPLE quantities concatenated, one per line
                    item, in the SAME ORDER as the LIN pairs were listed, OR
                    arrive as a single quantity immediately following one LIN
                    segment. Each entry is "qualifier:amount:unit".
                    -> for the Nth quantity entry, apply to line item N (or,
                       if only one entry, to the line item it immediately
                       follows in segment order within this batch):
                         E1EDP01/MENGE = amount
                         E1EDP01/MENEE = unit
                    (qualifier code itself, e.g. 21 vs 22, does not change the
                    IDoc target field -- both map to MENGE/MENEE for their
                    respective line item)
 
                PRI, price
                    format "AAA:20.00" -> field 2 is the net price
                    -> nested E1EDP20/NETWR on the line item it immediately
                       follows in segment order within this batch. If multiple
                       PRI values are present, apply positionally to line
                       items in the same order as their LIN entries.
                    NETWR MUST be wrapped inside its own <E1EDP20> element,
                    which is itself nested inside the <E1EDP01>. This is
                    WRONG:
                        <E1EDP01><NETWR>15.50</NETWR></E1EDP01>
                        <PRI><NETWR>15.50</NETWR></PRI>
                    This is CORRECT:
                        <E1EDP01><E1EDP20><NETWR>15.50</NETWR></E1EDP20></E1EDP01>
                    NETWR must never appear as a direct child of E1EDP01, and
                    never inside a <PRI> tag (PRI is an EDI name, not an IDoc
                    name, and must never appear in the output at all).
 
                UNH/UNT (envelope/control segments)
                    -> never map directly and never appear in the output.
 
                UNZ (interchange trailer)
                    -> the UNZ interchange reference number becomes
                       EDI_DC40/DOCNUM (zero-padded to 14 digits, e.g.
                       "1" -> "00000000000001"), and only on the batch
                       that actually contains a UNZ segment. UNZ itself is
                       never output as a tag.
                    DOCNUM LOCK (HARD RULE): if the current batch does NOT
                    contain a UNZ segment, do NOT populate, guess, or
                    invent a DOCNUM value. Either omit the <DOCNUM> tag
                    entirely or leave it exactly as it already appeared in
                    CURRENT IDOC (empty or previously set). A non-numeric
                    or non-zero-padded DOCNUM (e.g. "ABC123") is ALWAYS
                    wrong -- DOCNUM is numeric-only, 14 digits, zero-padded,
                    derived only from a real UNZ reference number.
 
                ==================================================
                LINE ITEM CORRELATION RULE (applies ACROSS the whole batch)
                ==================================================
 
                LIN, PIA, IMD, QTY, and PRI segments describing the same order
                may all appear together within one batch, or a line item may
                have been opened in a PREVIOUS batch and only get PIA/IMD/
                QTY/PRI additions in THIS batch. Process the segments listed
                below in the exact order given. Track which E1EDP01 (by
                POSEX) is "current" as you go, and attach PIA/IMD/QTY/PRI
                values to the correct POSEX using the positional and scope
                rules above. Never invent a new E1EDP01 for a POSEX that
                already exists in the CURRENT IDOC -- append nested children
                to the existing one instead, matched by its existing POSEX
                value.
 
                ==================================================
                GOLDEN EXAMPLE (follow this STRUCTURE exactly -- values shown
                are illustrative only; see "DO NOT COPY VALUES" rule above)
                ==================================================
 
                Given this batch of EDI segments (processed together, in
                order, in a single call):
 
                    DTM: 137:20260722:102
                    NAD: BY, 92, BUYERID::92
                    NAD: SE, 92, SUPPLIERID::92
                    CUX: 2:EUR:4
                    LIN: 1, ITEM1001A:SA, 2, ITEM1002:IN
                    IMD: F, ::WIDGET
                    QTY: 22:100:PCE, 21:50:PCE
                    PRI: AAA:15.50, AAA:20.00
 
                The correct resulting IDoc is:
 
                <?xml version="1.0" encoding="UTF-8"?>
                <ORDERS05>
                  <IDOC BEGIN="1">
                    <EDI_DC40>
                      <TABNAM>EDI_DC40</TABNAM>
                      <DOCNUM>00000000000001</DOCNUM>
                      <SNDPRN>SENDERID</SNDPRN>
                      <RCVPRN>RECEIVERID</RCVPRN>
                      <CREDAT>20260722</CREDAT>
                      <CRETIM>150000</CRETIM>
                    </EDI_DC40>
                    <E1EDK01>
                      <BELNR>PO12345</BELNR>
                      <DATUM>20260722</DATUM>
                      <WAERS>EUR</WAERS>
                    </E1EDK01>
                    <E1EDKA1>
                      <PARVW>BY</PARVW>
                      <PARTN>BUYERID</PARTN>
                    </E1EDKA1>
                    <E1EDKA1>
                      <PARVW>SU</PARVW>
                      <PARTN>SUPPLIERID</PARTN>
                    </E1EDKA1>
                    <E1EDP01>
                      <POSEX>1</POSEX>
                      <MENGE>100</MENGE>
                      <MENEE>PCE</MENEE>
                      <E1EDP19>
                        <QUALF>002</QUALF>
                        <IDNLF>ITEM1001A</IDNLF>
                      </E1EDP19>
                      <E1EDP20>
                        <NETWR>15.50</NETWR>
                      </E1EDP20>
                      <E1EDPT1>
                        <TDLINE>WIDGET</TDLINE>
                      </E1EDPT1>
                    </E1EDP01>
                    <E1EDP01>
                      <POSEX>2</POSEX>
                      <MENGE>50</MENGE>
                      <MENEE>PCE</MENEE>
                      <E1EDP19>
                        <QUALF>001</QUALF>
                        <IDNLF>ITEM1002</IDNLF>
                      </E1EDP19>
                      <E1EDP20>
                        <NETWR>20.00</NETWR>
                      </E1EDP20>
                    </E1EDP01>
                  </IDOC>
                </ORDERS05>
 
                Notice: no empty tags, no broken nesting, every value traced back
                to a real field in the source segments (not copied from this
                example), and IMD/QTY/PRI/PIA values distributed positionally
                and by scope across the two E1EDP01 line items -- all derived
                from a SINGLE batch call.
 
                ==================================================
                YOUR TASK
                ==================================================
 
                Update the existing SAP IDoc using the reference and example
                above. Map ALL of the EDI segments shown in "CURRENT EDI
                SEGMENTS" below, in the exact order given, in this ONE pass.
 
                Preserve all previously generated IDoc XML exactly as-is.
                Never modify or delete previous values. Never duplicate an
                existing value. If a segment in this batch belongs inside an
                existing IDoc segment (e.g. another NAD, another line item's
                QTY/PIA), append/attach the values per the rules above. If it
                starts a new repeating structure not yet present (E1EDP01,
                E1EDKA1, etc.), create a NEW SAP segment.
 
                ==================================================
                CURRENT IDOC
                ==================================================
 
                """);
 
        sb.append(previousIdocXml).append("\n\n");
 
        sb.append("""
                ==================================================
                CURRENT EDI SEGMENTS (process ALL of these, IN ORDER,
                in this single call)
                ==================================================
 
                """);
 
        int segNo = 1;
        for (EdiSegment segment : segments) {
            sb.append("Segment ")
                    .append(segNo++)
                    .append(" of ")
                    .append(segments.size())
                    .append(" -- name: ")
                    .append(segment.getName())
                    .append("\n");
            sb.append(segment.getRawXml());
            sb.append("\n\n");
        }
 
        sb.append("""
                ==================================================
                RETRIEVED MAPPING CHUNKS (reference only -- do not copy or
                output any XSLT syntax from these)
                ==================================================
 
                """);
 
        int i = 1;
 
        for (MappingChunk chunk : chunks) {
 
            sb.append("Chunk ")
                    .append(i++)
                    .append(" (score=")
                    .append(chunk.getScore())
                    .append(")\n");
 
            sb.append(chunk.getChunkText());
 
            sb.append("\n\n");
        }
 
        sb.append("""
                ==================================================
                OUTPUT REQUIREMENTS
                ==================================================
 
                Return ONLY the COMPLETE updated SAP IDoc XML, well-formed per
                the rules above, following the GOLDEN EXAMPLE structure (not
                its values), reflecting ALL segments from CURRENT EDI SEGMENTS
                merged into CURRENT IDOC.
 
                Never output XSLT (<xsl:*> of any kind).
                Never output EDI segment or field wrappers (<segment>, <field>,
                <UNB>, <UNH>, <UNT>, <UNZ>, <NAD>, <DTM>, <LIN>, <PIA>, <IMD>,
                <QTY>, <PRI>, <CUX>, <BGM>, <SENDERID>, <RECEIVERID>).
                Never output an empty tag when the source has a value.
                Never break tag nesting. Never leave a field (POSEX, MENGE,
                NETWR, etc.) floating outside its required wrapper element.
 
                BEFORE YOU RETURN YOUR ANSWER, silently verify all of the
                following against your own draft, and fix anything that fails:
                  1. Every opened tag has a matching close tag, same name,
                     same case, correctly nested -- no exceptions.
                  2. No EDI segment/field name (see list above) appears as an
                     output tag anywhere in the document.
                  3. EDI_DC40/CREDAT and CRETIM were NOT taken from DTM, and
                     were NOT changed if they already had values in the
                     CURRENT IDOC.
                  4. Every E1EDKA1/PARTN value has had everything from the
                     first colon onward stripped off (no "::92" suffixes
                     remain anywhere in the output).
                  5. Every E1EDP01/POSEX is a literal copy of a real LIN line
                     number from the EDI input -- not a count, not an
                     increment, not invented.
                  6. Every NETWR sits inside E1EDP20 inside E1EDP01 -- never
                     directly inside E1EDP01, never inside a <PRI> tag.
                  7. No value in your output matches a GOLDEN EXAMPLE value
                     (SU, ITEM1001A, ITEM1002, 15.50, 20.00, 20260722, etc.)
                     unless that exact value is also independently present in
                     the actual CURRENT EDI SEGMENTS or CURRENT IDOC below.
                  8. Every segment listed in CURRENT EDI SEGMENTS above has
                     been accounted for somewhere in the output -- none were
                     silently skipped.
 
                No explanation. No markdown. No comments. No XML declaration
                repeated more than once.
                """);
 
        return sb.toString();
    }

 








    
    
  public static String getEdiToXmlPrompt(String ediText) {
    return """
        You are an EDI parser.

        Your only task is to convert the supplied EDI document into XML.

        STRICT RULES

        1. Never guess.
        2. Never infer hierarchy.
        3. Never merge segments.
        4. Never rename segment names.
        5. Never classify segments into Header, Detail or Trailer.
        6. Never mix ANSI X12 and EDIFACT.
        7. Detect the standard only from the document.
        8. Detect the transaction type only from the document.
        9. Every EDI segment becomes exactly one XML element.
        10. Every data element becomes Element1, Element2, Element3...
        11. Preserve segment order.
        12. Preserve repeating segments.
        13. Preserve empty elements.
        14. Return XML only.
        15. Do not use markdown.
        16. Do not use ```xml.

        Output format:

        <EDI>
            <Standard>...</Standard>
            <TransactionType>...</TransactionType>

            <Segment name="UNB">
                <Element1>...</Element1>
                <Element2>...</Element2>
            </Segment>

            <Segment name="UNH">
                ...
            </Segment>

            <Segment name="BGM">
                ...
            </Segment>

        </EDI>

        Convert the following EDI document:

        """
        + ediText;
  }

  }

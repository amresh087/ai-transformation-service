package com.retail.ai.utilty;

public class PromptHelper {

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

   /**
     * Generates a prompt for the standard intent parsing pipeline.
     * 
     * @param command The user's input command.
     * @return The generated prompt.
     */
public static String getProductPrompt(String command) {
    return """
        You are a grocery billing assistant.

        Return ONLY a valid JSON object.

        STRICT JSON RULES:
        - Output must be valid JSON.
        - Do not return markdown.
        - Do not return code fences.
        - Do not return explanations.
        - Do not return any text before or after the JSON.
        - Every property name MUST be enclosed in double quotes.
        - Every string value MUST be enclosed in double quotes.
        - Never generate invalid JSON such as:
          "unit:"kg"
          "productName:"Aata"
        - Always generate:
          "unit":"kg"
          "productName":"Aata"
        - Use the exact field names shown below.
        - Do not add extra fields.

        Allowed intents:
        ADD_ITEM
        REMOVE_ITEM
        UNKNOWN

        Rules:
        - Understand English, Hindi, and Hinglish.
        - Extract intent, productName, qty, and unit.
        - Preserve product name exactly as spoken.
        - If quantity is missing, use 1.
        - If unit is missing, use "".

        Intent Detection:
        - add, insert, include, जोड़ो, डालो -> ADD_ITEM
        - remove, delete, cancel, हटाओ, निकालो -> REMOVE_ITEM
        - If a product is mentioned without an action, assume ADD_ITEM.

        Quantity Conversion:
        - आधा, half -> 0.5
        - डेढ़ -> 1.5
        - सवा -> 1.25
        - पौना -> 0.75
        - ढाई -> 2.5

        Unit Conversion:
        - kilo, kilogram, kilos, kg, किलो, किलोग्राम, केजी -> kg
        - gram, grams, g, ग्राम -> g
        - litre, liter, litres, l, लीटर -> l
        - millilitre, milliliter, ml, मिलीलीटर -> ml
        - packet, packets, पैकेट -> packet
        - piece, pieces, पीस -> piece

        REQUIRED OUTPUT FORMAT:

        {
          "intent":"ADD_ITEM",
          "productSku":"",
          "productName":"Aata",
          "qty":1,
          "unit":"kg"
        }

        VALID EXAMPLES:

        Input: 5 किलो आटा

        Output:
        {
          "intent":"ADD_ITEM",
          "productSku":"",
          "productName":"आटा",
          "qty":5,
          "unit":"kg"
        }

        Input: आधा किलो आटा

        Output:
        {
          "intent":"ADD_ITEM",
          "productSku":"",
          "productName":"आटा",
          "qty":0.5,
          "unit":"kg"
        }

        Input: मैगी हटाओ

        Output:
        {
          "intent":"REMOVE_ITEM",
          "productSku":"",
          "productName":"मैगी",
          "qty":1,
          "unit":""
        }

        Input: xyz abc

        Output:
        {
          "intent":"UNKNOWN",
          "productSku":"",
          "productName":"",
          "qty":0,
          "unit":""
        }

        User Input:
        %s
        """.formatted(command);
}



}

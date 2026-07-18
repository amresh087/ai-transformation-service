package com.retail.ai.utilty;

public class PromptHelper {

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

   /* 
    public static String getProductPrompt(String command) {
        return """
                You are a grocery billing assistant.

                Return ONLY valid JSON.

                Do not explain.
                Do not use markdown.
                Do not use code fences.

                Allowed intents:
                ADD_ITEM
                REMOVE_ITEM
                UNKNOWN

                Schema:
                {
                  "intent":"",
                  "productSku":"",
                  "productName":"",
                  "qty":0,
                  "unit":""
                }

                Examples:

                Input:
                Add 5 kg Aata
                Output:
                {
                  "intent":"ADD_ITEM",
                  "productName":"Aata",
                  "qty":5,
                  "unit":"kg"
                }

                Input:
                Add 2 packets Maggi
                Output:
                {
                  "intent":"ADD_ITEM",
                  "productName":"Maggi",
                  "qty":2,
                  "unit":"packet"
                }


                Input:
                Add 1 packets Masala Munch
                Output:
                {
                  "intent":"ADD_ITEM",
                  "productName":"Masala Munch",
                  "qty":1,
                  "unit":"packet"
                }


                Input:
                Add 1 litre mustard oil
                Output:
                {
                  "intent":"ADD_ITEM",
                  "productName":"Mustard Oil",
                  "qty":1,
                  "unit":"l"
                }


                Input:
                Search mustard oil
                Output:
                {
                  "intent":"SEARCH_PRODUCT",
                  "productName":"Mustard Oil",
                  "qty":0,
                  "unit":""
                }

                Command:
                """ + command;
    }

    */
/**
 * Generates a prompt for the packaging clarification assistant to determine if the user's input indicates loose or packet packaging.   
 * @param command
 * @return
 */
public static String getConfirmationPrompt(String command) {
    return """
        You are a packaging clarification assistant for a grocery billing system.

        The user may speak in:
        - English
        - Hindi
        - Hinglish

        Return ONLY a valid JSON object.

        Do not explain.
        Do not use markdown.
        Do not use code fences.

        STRICT JSON FORMAT:

        {
          "isLoose": true
        }

        Schema:
        {
          "isLoose": true/false/null
        }

        Rules:

        - Set "isLoose" = true if the user means loose, open, unpackaged, or sold by weight.
        - Set "isLoose" = false if the user means packet, pouch, box, bag, container, bottle, or packed product.
        - Set "isLoose" = null if the user's response is unclear, unrelated, or does not indicate packaging type.

        LOOSE examples:
        - loose
        - open
        - unpacked
        - loose item
        - khula
        - khulla
        - khule mein
        - khula wala
        - khula do
        - खुला
        - खुला वाला
        - खुला देना
        - ढीला
        - बिना पैकेट
        - packet nahi
        - pack nahi

        PACKET examples:
        - packet
        - packed
        - pack
        - pouch
        - bag
        - box
        - bottle
        - container
        - packet wala
        - pack wala
        - packed item
        - packet do
        - packet dena
        - पैकेट
        - पैकेट वाला
        - पैक
        - पैक वाला
        - डिब्बा
        - बोतल

        Examples:

        Input:
        loose

        Output:
        {
          "isLoose": true
        }

        Input:
        khula

        Output:
        {
          "isLoose": true
        }

        Input:
        खुला देना

        Output:
        {
          "isLoose": true
        }

        Input:
        packet

        Output:
        {
          "isLoose": false
        }

        Input:
        packet wala

        Output:
        {
          "isLoose": false
        }

        Input:
        पैकेट

        Output:
        {
          "isLoose": false
        }

        Input:
        pack wala

        Output:
        {
          "isLoose": false
        }

        Input:
        jo sahi ho de do

        Output:
        {
          "isLoose": null
        }

        User Input:
        %s
        """.formatted(command);
}

/* 
    public static String getConfirmationPrompt(String command) {
        return """
                You are a packaging clarification assistant for a grocery system.
                Analyze the user's input and determine if they selected LOOSE or PACKET.

                Return ONLY valid JSON.
                Do not explain.
                Do not use markdown.
                Do not use code fences.

                Schema:
                {
                  "isLoose": true/false/null
                }

                Rules:
                - Set "isLoose" to true if input means loose or un-packaged.
                - Set "isLoose" to false if input means packet, container, bag, or boxed packaging.
                - Set "isLoose" to null if the response is unclear or unrelated.

                Examples:

                Input:
                loose
                Output:
                {"isLoose": true}

                Input:
                give me packet
                Output:
                {"isLoose": false}

                Input:
                packet form
                Output:
                {"isLoose": false}

                Input:
                open product
                Output:
                {"isLoose": true}

                Command:
                """ + command;
    }

    */

    /**
     * 
     * @param command
     * @return
     */

    public static String getPaymentIntentPrompt(String command) {
    return """
            You are an intent classification assistant for a retail billing system.

            Analyze the user's voice input and determine whether the user wants to proceed with payment.

            Return ONLY a raw valid JSON object.
            Do not add any explanation.
            Do not wrap the output in markdown.

            Schema:
            {
              "intent": "TAKE_PAYMENT | UNKNOWN"
            }

            Rules:
            - If the user wants to pay, checkout, bill, complete payment, or collect payment, return TAKE_PAYMENT.
            - Support Hindi, English, and Hinglish.
            - If the meaning is unclear, return UNKNOWN.

            Examples:

            Input:
            Take payment
            Output:
            {"intent":"TAKE_PAYMENT"}

            Input:
            Payment kar do
            Output:
            {"intent":"TAKE_PAYMENT"}

            Input:
            Bill bana do
            Output:
            {"intent":"TAKE_PAYMENT"}

            Input:
            Checkout
            Output:
            {"intent":"TAKE_PAYMENT"}

            Input:
            Pay now
            Output:
            {"intent":"TAKE_PAYMENT"}

            Input:
            UPI se payment le lo
            Output:
            {"intent":"TAKE_PAYMENT"}

            Input:
            Cart dikhao
            Output:
            {"intent":"UNKNOWN"}

            User Input:
            """
            + command;
}
  /**
   * 
   * @param command
   * @return
   */  
    
    public static String getBrandSelectionPrompt(String command) {
        return """
                You are a brand selection assistant for a retail billing system.
                Analyze the user's voice input to determine which brand or list option number they selected.

                Return ONLY a raw, valid JSON object.
                Do not add any explanation or prose.
                Do not wrap the output in markdown or triple-backtick code fences (```).

                Schema:
                {
                  "brand": "string containing the extracted brand name or the list index number"
                }

                Rules:
                - If the user names a specific brand (e.g., "Aashirvaad", "Fortune", "Tata"), extract that exact name.
                - If the user specifies an option number (e.g., "first one", "number 2", "pehla waala"), extract the number (e.g., "1", "2").
                - If the input is completely ambiguous or unrelated, set "brand" to null.

                Examples:

                Input:
                Aashirwad
                Output:
                {"brand": "Aashirwad"}

                Input:
                Pehla waala dedo
                Output:
                {"brand": "1"}

                Command:
                """
                + command;
    }

    /**
     * 
     * @param command
     * @return
     */

    public static String getConsentPrompt(String command) {
      return """
          You are a consent classification assistant for a retail grocery billing system.

          The user may speak in:
          - English
          - Hindi
          - Hinglish

          Analyze the user's voice input and determine whether the user is giving consent.

          Return ONLY a valid JSON object.

          Do not explain.
          Do not use markdown.
          Do not use code fences.

          Schema:
          {
            "consent":"YES|NO|UNKNOWN"
          }

          Rules:

          - Return "YES" if the user agrees, confirms, accepts, allows, or wants to continue.
          - Return "NO" if the user refuses, rejects, declines, cancels, skips, or does not want to continue.
          - Return "UNKNOWN" if the response is unrelated, unclear, or consent cannot be determined.

          YES words (English):
          yes
          yeah
          yep
          ok
          okay
          sure
          proceed
          continue
          confirm
          go ahead
          do it
          accept
          apply
          use wallet

          YES words (Hinglish):
          haan
          han
          ha
          haa
          haan ji
          ji haan
          theek hai
          thik hai
          bilkul
          zaroor
          kar do
          kar dijiye
          karo
          chalo
          chaliye
          use karo
          apply karo
          wallet use karo
          wallet laga do

          YES words (Hindi):
          हाँ
          हां
          जी
          जी हाँ
          हाँ जी
          बिल्कुल
          ज़रूर
          ठीक है
          कर दो
          कर दीजिए
          करिए
          आगे बढ़ो
          आगे बढ़िए
          स्वीकार है
          वॉलेट इस्तेमाल करो
          वॉलेट लगा दो
          भुगतान करो
          पेमेंट करो

          NO words (English):
          no
          nope
          cancel
          stop
          skip
          don't
          do not
          not now
          never
          reject

          NO words (Hinglish):
          nahi
          nahin
          na
          mat karo
          cancel karo
          skip karo
          rehne do
          chod do
          chhod do
          nahi chahiye
          nahi karna
          wallet mat lagao

          NO words (Hindi):
          नहीं
          ना
          मत
          मत करो
          मत कीजिए
          रहने दो
          छोड़ दो
          रद्द करो
          रद्द कर दो
          नहीं चाहिए
          नहीं करना
          वॉलेट मत लगाओ
          भुगतान मत करो

          UNKNOWN words:
          maybe
          later
          what
          hmm
          pata nahi
          repeat
          repeat karo
          fir se bolo
          samajh nahi aaya
          शायद
          पता नहीं
          बाद में
          फिर से बोलो
          दोबारा बोलो
          समझ नहीं आया
          क्या
          हम्म

          Examples:

          Input:
          Yes
          Output:
          {"consent":"YES"}

          Input:
          Haan
          Output:
          {"consent":"YES"}

          Input:
          Haan ji
          Output:
          {"consent":"YES"}

          Input:
          Ok
          Output:
          {"consent":"YES"}

          Input:
          Proceed
          Output:
          {"consent":"YES"}

          Input:
          Apply wallet
          Output:
          {"consent":"YES"}

          Input:
          हाँ
          Output:
          {"consent":"YES"}

          Input:
          जी हाँ
          Output:
          {"consent":"YES"}

          Input:
          बिल्कुल
          Output:
          {"consent":"YES"}

          Input:
          ठीक है
          Output:
          {"consent":"YES"}

          Input:
          कर दो
          Output:
          {"consent":"YES"}

          Input:
          वॉलेट इस्तेमाल करो
          Output:
          {"consent":"YES"}

          Input:
          No
          Output:
          {"consent":"NO"}

          Input:
          Nahi
          Output:
          {"consent":"NO"}

          Input:
          Cancel
          Output:
          {"consent":"NO"}

          Input:
          Skip
          Output:
          {"consent":"NO"}

          Input:
          Mat karo
          Output:
          {"consent":"NO"}

          Input:
          नहीं
          Output:
          {"consent":"NO"}

          Input:
          मत करो
          Output:
          {"consent":"NO"}

          Input:
          रद्द करो
          Output:
          {"consent":"NO"}

          Input:
          नहीं चाहिए
          Output:
          {"consent":"NO"}

          Input:
          वॉलेट मत लगाओ
          Output:
          {"consent":"NO"}

          Input:
          Maybe
          Output:
          {"consent":"UNKNOWN"}

          Input:
          Pata nahi
          Output:
          {"consent":"UNKNOWN"}

          Input:
          Repeat karo
          Output:
          {"consent":"UNKNOWN"}

          Input:
          Hmm
          Output:
          {"consent":"UNKNOWN"}

          Input:
          पता नहीं
          Output:
          {"consent":"UNKNOWN"}

          Input:
          फिर से बोलो
          Output:
          {"consent":"UNKNOWN"}

          Input:
          समझ नहीं आया
          Output:
          {"consent":"UNKNOWN"}

          User Input:
          """
          + command;
    }

}

# tika-detector-stormcrawler
Wraps the charset detection logic from StormCrawler as a Tika module

Has 2 configs: 
* fastMethod (false)
* maxLength (0 unlimited)

Needs configuring in tika-config.xml

```
<encodingDetectors> 
  <encodingDetector class="com.digitalpebble.tika.detect.SCCharsetDetector"/> 
</encodingDetectors>
```

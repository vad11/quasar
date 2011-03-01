package blueeyes.core.http

import org.specs.Specification

class HttpNumberSpec extends Specification {

  "HttpNumbers:  Should return ContentLength or parse to None on bad input" in {
    HttpNumbers.parseHttpNumbers("bees") mustEqual None
  }
}

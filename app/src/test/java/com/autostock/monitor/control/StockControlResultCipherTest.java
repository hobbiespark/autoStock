package com.autostock.monitor.control;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StockControlResultCipherTest {
 final StockControlResultCipher cipher=new StockControlResultCipher("test_key",new byte[32]);
 StockControlResultCipher.Result result(){return new StockControlResultCipher.Result(StockControlResultCipher.Outcome.succeeded,"synthetic","private 0427");}
 @Test void encryptionIsRandomizedAndCanonicalResultHashIsStable(){var a=cipher.seal(result(),"scope:1");var b=cipher.seal(result(),"scope:1");assertNotEquals(a.encrypted(),b.encrypted());assertEquals(a.sha256(),b.sha256());assertEquals(result(),cipher.open(a.keyId(),a.encrypted(),a.sha256(),"scope:1"));assertFalse(result().toString().contains("0427"));}
 @Test void otherContextKeyHashAndCiphertextAreRejected(){var a=cipher.seal(result(),"scope:1");assertThrows(SecurityException.class,()->cipher.open(a.keyId(),a.encrypted(),a.sha256(),"scope:2"));assertThrows(SecurityException.class,()->cipher.open("other",a.encrypted(),a.sha256(),"scope:1"));assertThrows(SecurityException.class,()->cipher.open(a.keyId(),a.encrypted(),"a".repeat(64),"scope:1"));byte[] changed=java.util.Base64.getDecoder().decode(a.encrypted());changed[0]^=1;assertThrows(SecurityException.class,()->cipher.open(a.keyId(),java.util.Base64.getEncoder().encodeToString(changed),a.sha256(),"scope:1"));}
 @Test void invalidResultsCannotBeEncoded(){assertThrows(IllegalArgumentException.class,()->new StockControlResultCipher.Result(null,"code","reply"));assertThrows(IllegalArgumentException.class,()->new StockControlResultCipher.Result(StockControlResultCipher.Outcome.succeeded,"BAD","reply"));assertThrows(IllegalArgumentException.class,()->new StockControlResultCipher.Result(StockControlResultCipher.Outcome.succeeded,"code","x".repeat(3001)));}
}

/*
 *
 * Copyright (c) 2013 - 2020 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.scep.util;

import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CRLException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.text.ParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.BERTags;
import org.bouncycastle.asn1.DERGeneralizedTime;
import org.bouncycastle.asn1.DERUTCTime;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.RSASSAPSSparams;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.CertificateList;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.xipki.security.HashAlgo;
import org.xipki.security.X509Cert;
import org.xipki.util.Args;

/**
 * SCEP utility class.
 *
 * @author Lijun Liao
 */

public class ScepUtil {

  private ScepUtil() {
  }

  /*
   * The first one is a non-CA certificate if there exists one non-CA certificate.
   */
  public static List<X509Cert> getCertsFromSignedData(SignedData signedData)
      throws CertificateException {
    Args.notNull(signedData, "signedData");
    ASN1Set set = signedData.getCertificates();
    if (set == null) {
      return Collections.emptyList();
    }

    final int n = set.size();
    if (n == 0) {
      return Collections.emptyList();
    }

    List<X509Cert> certs = new LinkedList<>();

    X509Cert eeCert = null;
    for (int i = 0; i < n; i++) {
      X509Cert cert;
      try {
        cert = new X509Cert(Certificate.getInstance(set.getObjectAt(i)));
      } catch (IllegalArgumentException ex) {
        throw new CertificateException(ex);
      }

      if (eeCert == null && cert.getBasicConstraints() == -1) {
        eeCert = cert;
      } else {
        certs.add(cert);
      }
    }

    if (eeCert != null) {
      certs.add(0, eeCert);
    }

    return certs;
  } // method getCertsFromSignedData

  public static X509CRLHolder getCrlFromPkiMessage(SignedData signedData) throws CRLException {
    Args.notNull(signedData, "signedData");
    ASN1Set set = signedData.getCRLs();
    if (set == null || set.size() == 0) {
      return null;
    }

    try {
      CertificateList cl = CertificateList.getInstance(set.getObjectAt(0));
      return new X509CRLHolder(cl);
    } catch (IllegalArgumentException ex) {
      throw new CRLException(ex);
    }
  } // method getCrlFromPkiMessage

  public static String getSignatureAlgorithm(PrivateKey key, HashAlgo hashAlgo) {
    Args.notNull(key, "key");
    Args.notNull(hashAlgo, "hashAlgo");
    String algorithm = key.getAlgorithm();
    if ("RSA".equalsIgnoreCase(algorithm)) {
      return hashAlgo.getName() + "withRSA";
    } else {
      throw new UnsupportedOperationException(
          "getSignatureAlgorithm() for non-RSA is not supported yet.");
    }
  }

  public static ASN1ObjectIdentifier extractDigesetAlgorithmIdentifier(String sigOid,
      byte[] sigParams) throws NoSuchAlgorithmException {
    Args.notBlank(sigOid, "sigOid");

    ASN1ObjectIdentifier algOid = new ASN1ObjectIdentifier(sigOid);

    ASN1ObjectIdentifier digestAlgOid;
    if (PKCSObjectIdentifiers.md5WithRSAEncryption.equals(algOid)) {
      digestAlgOid = PKCSObjectIdentifiers.md5;
    } else if (PKCSObjectIdentifiers.sha1WithRSAEncryption.equals(algOid)) {
      digestAlgOid = X509ObjectIdentifiers.id_SHA1;
    } else if (PKCSObjectIdentifiers.sha224WithRSAEncryption.equals(algOid)) {
      digestAlgOid = NISTObjectIdentifiers.id_sha224;
    } else if (PKCSObjectIdentifiers.sha256WithRSAEncryption.equals(algOid)) {
      digestAlgOid = NISTObjectIdentifiers.id_sha256;
    } else if (PKCSObjectIdentifiers.sha384WithRSAEncryption.equals(algOid)) {
      digestAlgOid = NISTObjectIdentifiers.id_sha384;
    } else if (PKCSObjectIdentifiers.sha512WithRSAEncryption.equals(algOid)) {
      digestAlgOid = NISTObjectIdentifiers.id_sha512;
    } else if (PKCSObjectIdentifiers.id_RSASSA_PSS.equals(algOid)) {
      RSASSAPSSparams param = RSASSAPSSparams.getInstance(sigParams);
      digestAlgOid = param.getHashAlgorithm().getAlgorithm();
    } else {
      throw new NoSuchAlgorithmException("unknown signature algorithm" + algOid.getId());
    }

    return digestAlgOid;
  } // method extractDigesetAlgorithmIdentifier

  public static ASN1Encodable getFirstAttrValue(AttributeTable attrs, ASN1ObjectIdentifier type) {
    Args.notNull(attrs, "attrs");
    Args.notNull(type, "type");
    Attribute attr = attrs.get(type);
    if (attr == null) {
      return null;
    }
    ASN1Set set = attr.getAttrValues();
    return (set.size() == 0) ? null : set.getObjectAt(0);
  }

  public static void addCmsCertSet(CMSSignedDataGenerator generator, X509Cert[] cmsCertSet)
      throws CertificateEncodingException, CMSException {
    if (cmsCertSet == null || cmsCertSet.length == 0) {
      return;
    }
    Args.notNull(generator, "geneator");
    Collection<X509CertificateHolder> certColl = new LinkedList<>();
    for (X509Cert m : cmsCertSet) {
      certColl.add(m.toBcCert());
    }

    JcaCertStore certStore = new JcaCertStore(certColl);
    generator.addCertificates(certStore);
  } // method addCmsCertSet

  public static Date getTime(Object obj) {
    if (obj instanceof byte[]) {
      byte[] encoded = (byte[]) obj;
      int tag = encoded[0] & 0xFF;;
      try {
        if (tag == BERTags.UTC_TIME) {
          return DERUTCTime.getInstance(encoded).getDate();
        } else if (tag == BERTags.GENERALIZED_TIME) {
          return DERGeneralizedTime.getInstance(encoded).getDate();
        } else {
          throw new IllegalArgumentException("invalid tag " + tag);
        }
      } catch (ParseException ex) {
        throw new IllegalArgumentException("error parsing time", ex);
      }
    } else if (obj instanceof Time) {
      return ((Time) obj).getDate();
    } else if (obj instanceof org.bouncycastle.asn1.cms.Time) {
      return ((org.bouncycastle.asn1.cms.Time) obj).getDate();
    } else {
      return Time.getInstance(obj).getDate();
    }
  } // method getTime

}

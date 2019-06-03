/*
 *
 * Copyright (c) 2013 - 2019 Lijun Liao
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

package org.xipki.qa.ca;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1UTCTime;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.api.profile.Certprofile.X509CertVersion;
import org.xipki.ca.api.profile.CertprofileException;
import org.xipki.ca.certprofile.xijson.XijsonCertprofile;
import org.xipki.ca.certprofile.xijson.conf.ExtensionType;
import org.xipki.ca.certprofile.xijson.conf.X509ProfileType;
import org.xipki.qa.ValidationIssue;
import org.xipki.qa.ValidationResult;
import org.xipki.security.util.AlgorithmUtil;
import org.xipki.security.util.X509Util;
import org.xipki.util.Args;
import org.xipki.util.CollectionUtil;
import org.xipki.util.InvalidConfException;
import org.xipki.util.LogUtil;
import org.xipki.util.StringUtil;
import org.xipki.util.Validity;

/**
 * QA for Certprofile.
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class CertprofileQa {

  private static final Logger LOG = LoggerFactory.getLogger(CertprofileQa.class);

  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  private static final long SECOND = 1000L;

  private static final long MAX_CERT_TIME_MS = 253402300799982L; //9999-12-31-23-59-59

  private static final long EPOCHTIME_2050010100 = 2524608000L;

  private final SubjectChecker subjectChecker;

  private final PublicKeyChecker publicKeyChecker;

  private final ExtensionsChecker extensionsChecker;

  private final XijsonCertprofile certprofile;

  public CertprofileQa(String data) throws CertprofileException {
    this(
        StringUtil.toUtf8Bytes(
            Args.notNull(data, "data")));
  }

  public CertprofileQa(byte[] dataBytes) throws CertprofileException {
    Args.notNull(dataBytes, "dataBytes");
    try {
      X509ProfileType conf = X509ProfileType.parse(new ByteArrayInputStream(dataBytes));

      certprofile = new XijsonCertprofile();
      certprofile.initialize(conf);

      this.publicKeyChecker = new PublicKeyChecker(certprofile.getKeyAlgorithms());
      this.subjectChecker = new SubjectChecker(certprofile.getSubjectControl());
      this.extensionsChecker = new ExtensionsChecker(conf, certprofile);
    } catch (RuntimeException ex) {
      LogUtil.error(LOG, ex);
      throw new CertprofileException(
          "RuntimeException thrown while initializing certprofile: " + ex.getMessage());
    }
  } // constructor

  public ValidationResult checkCert(byte[] certBytes, IssuerInfo issuerInfo,
      X500Name requestedSubject, SubjectPublicKeyInfo requestedPublicKey,
      Extensions requestedExtensions) {
    Args.notNull(certBytes, "certBytes");
    Args.notNull(issuerInfo, "issuerInfo");
    Args.notNull(requestedSubject, "requestedSubject");
    Args.notNull(requestedPublicKey, "requestedPublicKey");

    List<ValidationIssue> resultIssues = new LinkedList<ValidationIssue>();

    Certificate bcCert;
    TBSCertificate tbsCert;
    X509Certificate cert;
    ValidationIssue issue;

    // certificate size
    issue = new ValidationIssue("X509.SIZE", "certificate size");
    resultIssues.add(issue);

    certBytes = X509Util.toDerEncoded(certBytes);

    Integer maxSize = certprofile.getMaxSize();
    if (maxSize != 0) {
      int size = certBytes.length;
      if (size > maxSize) {
        issue.setFailureMessage(String.format(
            "certificate exceeds the maximal allowed size: %d > %d", size, maxSize));
      }
    }

    // certificate encoding
    issue = new ValidationIssue("X509.ENCODING", "certificate encoding");
    resultIssues.add(issue);
    try {
      bcCert = Certificate.getInstance(certBytes);
      tbsCert = bcCert.getTBSCertificate();
      cert = X509Util.parseCert(certBytes);
    } catch (CertificateException ex) {
      issue.setFailureMessage("certificate is not corrected encoded");
      return new ValidationResult(resultIssues);
    }

    // syntax version
    issue = new ValidationIssue("X509.VERSION", "certificate version");
    resultIssues.add(issue);
    int versionNumber = tbsCert.getVersionNumber();

    X509CertVersion expVersion = certprofile.getVersion();
    if (versionNumber != expVersion.getVersionNumber()) {
      issue.setFailureMessage("is '" + versionNumber
          + "' but expected '" + expVersion.getVersionNumber() + "'");
    }

    // serialNumber
    issue = new ValidationIssue("X509.serialNumber", "certificate serial number");
    resultIssues.add(issue);
    BigInteger serialNumber = tbsCert.getSerialNumber().getValue();
    if (serialNumber.signum() != 1) {
      issue.setFailureMessage("not positive");
    } else {
      if (serialNumber.bitLength() >= 160) {
        issue.setFailureMessage("serial number has more than 20 octets");
      }
    }

    // signatureAlgorithm
    List<String> signatureAlgorithms = certprofile.getSignatureAlgorithms();
    if (CollectionUtil.isNotEmpty(signatureAlgorithms)) {
      issue = new ValidationIssue("X509.SIGALG", "signature algorithm");
      resultIssues.add(issue);

      AlgorithmIdentifier sigAlgId = bcCert.getSignatureAlgorithm();
      AlgorithmIdentifier tbsSigAlgId = tbsCert.getSignature();
      if (!tbsSigAlgId.equals(sigAlgId)) {
        issue.setFailureMessage(
            "Certificate.tbsCertificate.signature != Certificate.signatureAlgorithm");
      }

      try {

        String sigAlgo = AlgorithmUtil.getSignatureAlgoName(sigAlgId);
        if (!issue.isFailed()) {
          if (!signatureAlgorithms.contains(sigAlgo)) {
            issue.setFailureMessage("signatureAlgorithm '" + sigAlgo + "' is not allowed");
          }
        }

        // check parameters
        if (!issue.isFailed()) {
          AlgorithmIdentifier expSigAlgId = AlgorithmUtil.getSigAlgId(sigAlgo);
          if (!expSigAlgId.equals(sigAlgId)) {
            issue.setFailureMessage("invalid parameters");
          }
        }
      } catch (NoSuchAlgorithmException ex) {
        issue.setFailureMessage("unsupported signature algorithm "
            + sigAlgId.getAlgorithm().getId());
      }
    }

    // notBefore encoding
    issue = new ValidationIssue("X509.NOTBEFORE.ENCODING", "notBefore encoding");
    checkTime(tbsCert.getStartDate(), issue);

    // notAfter encoding
    issue = new ValidationIssue("X509.NOTAFTER.ENCODING", "notAfter encoding");
    checkTime(tbsCert.getStartDate(), issue);

    if (certprofile.getNotBeforeOption().getMidNightTimeZone() != null) {
      issue = new ValidationIssue("X509.NOTBEFORE", "notBefore midnight");
      resultIssues.add(issue);
      Calendar cal = Calendar.getInstance(UTC);
      cal.setTime(cert.getNotBefore());
      int minute = cal.get(Calendar.MINUTE);
      int second = cal.get(Calendar.SECOND);

      if (minute != 0 || second != 0) {
        issue.setFailureMessage(" '" + cert.getNotBefore() + "' is not midnight time");
      }
    }

    // validity
    issue = new ValidationIssue("X509.VALIDITY", "cert validity");
    resultIssues.add(issue);

    if (cert.getNotAfter().before(cert.getNotBefore())) {
      issue.setFailureMessage("notAfter may not be before notBefore");
    } else if (cert.getNotBefore().before(issuerInfo.getCaNotBefore())) {
      issue.setFailureMessage("notBefore may not be before CA's notBefore");
    } else {
      Validity validity = certprofile.getValidity();
      Date expectedNotAfter = validity.add(cert.getNotBefore());
      if (expectedNotAfter.getTime() > MAX_CERT_TIME_MS) {
        expectedNotAfter = new Date(MAX_CERT_TIME_MS);
      }

      if (issuerInfo.isCutoffNotAfter()
          && expectedNotAfter.after(issuerInfo.getCaNotAfter())) {
        expectedNotAfter = issuerInfo.getCaNotAfter();
      }

      if (Math.abs(expectedNotAfter.getTime() - cert.getNotAfter().getTime()) > 60 * SECOND) {
        issue.setFailureMessage("cert validity is not within " + validity.toString());
      }
    }

    // subjectPublicKeyInfo
    resultIssues.addAll(publicKeyChecker.checkPublicKey(bcCert.getSubjectPublicKeyInfo(),
        requestedPublicKey));

    // Signature
    issue = new ValidationIssue("X509.SIG", "whether certificate is signed by CA");
    resultIssues.add(issue);
    try {
      cert.verify(issuerInfo.getCert().getPublicKey(), "BC");
    } catch (Exception ex) {
      issue.setFailureMessage("invalid signature");
    }

    // issuer
    issue = new ValidationIssue("X509.ISSUER", "certificate issuer");
    resultIssues.add(issue);
    if (!cert.getIssuerX500Principal().equals(issuerInfo.getCert().getSubjectX500Principal())) {
      issue.setFailureMessage("issue in certificate does not equal the subject of CA certificate");
    }

    // subject
    resultIssues.addAll(subjectChecker.checkSubject(bcCert.getSubject(), requestedSubject));

    // issuerUniqueID
    issue = new ValidationIssue("X509.IssuerUniqueID", "issuerUniqueID");
    resultIssues.add(issue);
    if (tbsCert.getIssuerUniqueId() != null) {
      issue.setFailureMessage("is present but not permitted");
    }

    // subjectUniqueID
    issue = new ValidationIssue("X509.SubjectUniqueID", "subjectUniqueID");
    resultIssues.add(issue);
    if (tbsCert.getSubjectUniqueId() != null) {
      issue.setFailureMessage("is present but not permitted");
    }

    // extensions
    issue = new ValidationIssue("X509.GrantedSubject", "grantedSubject");
    resultIssues.add(issue);

    resultIssues.addAll(extensionsChecker.checkExtensions(bcCert, issuerInfo, requestedExtensions,
        requestedSubject));

    return new ValidationResult(resultIssues);
  } // method checkCert

  public static Map<ASN1ObjectIdentifier, QaExtensionValue> buildConstantExtesions(
      Map<String, ExtensionType> extensionsType) throws CertprofileException {
    if (extensionsType == null) {
      return null;
    }

    Map<ASN1ObjectIdentifier, QaExtensionValue> map = new HashMap<>();

    for (String type : extensionsType.keySet()) {
      ExtensionType extn = extensionsType.get(type);
      if (extn.getConstant() == null) {
        continue;
      }

      ASN1ObjectIdentifier oid = new ASN1ObjectIdentifier(type);
      if (Extension.subjectAlternativeName.equals(oid) || Extension.subjectInfoAccess.equals(oid)
          || Extension.biometricInfo.equals(oid)) {
        continue;
      }

      byte[] encodedValue;
      try {
        encodedValue = extn.getConstant().toASN1Encodable().toASN1Primitive().getEncoded();
      } catch (IOException | InvalidConfException ex) {
        throw new CertprofileException(
            "could not parse the constant extension value of type" + type, ex);
      }

      QaExtensionValue extension = new QaExtensionValue(extn.isCritical(), encodedValue);
      map.put(oid, extension);
    }

    if (CollectionUtil.isEmpty(map)) {
      return null;
    }

    return Collections.unmodifiableMap(map);
  } // method buildConstantExtesions

  private static void checkTime(Time time, ValidationIssue issue) {
    ASN1Primitive asn1Time = time.toASN1Primitive();
    if (time.getDate().getTime() / 1000 < EPOCHTIME_2050010100) {
      if (!(asn1Time instanceof ASN1UTCTime)) {
        issue.setFailureMessage("not encoded as UTCTime");
      }
    } else {
      if (!(asn1Time instanceof ASN1GeneralizedTime)) {
        issue.setFailureMessage("not encoded as GeneralizedTime");
      }
    }
  }

}

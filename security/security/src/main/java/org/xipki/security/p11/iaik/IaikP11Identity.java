/*
 * Copyright (c) 2014 Lijun Liao
 *
 * TO-BE-DEFINE
 *
 */

package org.xipki.security.p11.iaik;

import java.io.IOException;
import java.math.BigInteger;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.xipki.common.IoCertUtil;
import org.xipki.common.ParamChecker;
import org.xipki.security.api.SignerException;
import org.xipki.security.api.p11.P11SlotIdentifier;
import org.xipki.security.api.p11.P11KeyIdentifier;

/**
 * @author Lijun Liao
 */

class IaikP11Identity implements Comparable<IaikP11Identity>
{
    private final P11SlotIdentifier slotId;
    private final P11KeyIdentifier keyId;

    private final X509Certificate[] certificateChain;
    private final PublicKey publicKey;
    private final int signatureKeyBitLength;

    public IaikP11Identity(
            P11SlotIdentifier slotId,
            P11KeyIdentifier keyId,
            X509Certificate[] certificateChain,
            PublicKey publicKey)
    {
        ParamChecker.assertNotNull("slotId", slotId);
        ParamChecker.assertNotNull("keyId", keyId);

        if((certificateChain == null || certificateChain.length < 1 || certificateChain[0] == null)
                && publicKey == null)
        {
            throw new IllegalArgumentException("Neither certificate nor publicKey is non-null");
        }

        this.slotId = slotId;
        this.keyId = keyId;
        this.certificateChain = certificateChain;
        this.publicKey = publicKey == null ? certificateChain[0].getPublicKey() : publicKey;

        if(this.publicKey instanceof RSAPublicKey)
        {
            signatureKeyBitLength = ((RSAPublicKey) this.publicKey).getModulus().bitLength();
        }
        else if(this.publicKey instanceof ECPublicKey)
        {
            signatureKeyBitLength = ((ECPublicKey) this.publicKey).getParams().getCurve().getField().getFieldSize();
        }
        else if(this.publicKey instanceof DSAPublicKey)
        {
            signatureKeyBitLength = ((DSAPublicKey) this.publicKey).getParams().getQ().bitLength();
        }
        else
        {
            throw new IllegalArgumentException("Currently only RSA, DSA and EC public key are supported, but not " +
                    this.publicKey.getAlgorithm() + " (class: " + this.publicKey.getClass().getName() + ")");
        }
    }

    public P11KeyIdentifier getKeyId()
    {
        return keyId;
    }

    public X509Certificate getCertificate()
    {
        return (certificateChain != null && certificateChain.length > 0) ? certificateChain[0] : null;
    }

    public X509Certificate[] getCertificateChain()
    {
        return certificateChain;
    }

    public PublicKey getPublicKey()
    {
        return publicKey == null ? certificateChain[0].getPublicKey() : publicKey;
    }

    public P11SlotIdentifier getSlotId()
    {
        return slotId;
    }

    public boolean match(P11SlotIdentifier slotId, P11KeyIdentifier keyId)
    {
        if(this.slotId.equals(slotId) == false)
        {
            return false;
        }

        return this.keyId.equals(keyId);
    }

    public boolean match(P11SlotIdentifier slotId, String keyLabel)
    {
        if(keyLabel == null)
        {
            return false;
        }

        return this.slotId.equals(slotId) && keyLabel.equals(keyId.getKeyLabel());
    }

    public byte[] CKM_RSA_PKCS(IaikExtendedModule module,
            byte[] encodedDigestInfo)
    throws SignerException
    {
        if(publicKey instanceof RSAPublicKey == false)
        {
            throw new SignerException("Operation CKM_RSA_PKCS is not allowed for " +
                    publicKey.getAlgorithm() + " public key");
        }

        IaikExtendedSlot slot = module.getSlot(slotId);
        if(slot == null)
        {
            throw new SignerException("Could not find slot " + slotId);
        }

        return slot.CKM_RSA_PKCS(encodedDigestInfo, keyId);
    }

    public byte[] CKM_RSA_X_509(IaikExtendedModule module,
            byte[] hash)
    throws SignerException
    {
        if(publicKey instanceof RSAPublicKey == false)
        {
            throw new SignerException("Operation CKM_RSA_X_509 is not allowed for " +
                    publicKey.getAlgorithm() + " public key");
        }

        IaikExtendedSlot slot = module.getSlot(slotId);
        if(slot == null)
        {
            throw new SignerException("Could not find slot " + slotId);
        }

        return slot.CKM_RSA_X509(hash, keyId);
    }

    public byte[] CKM_ECDSA(IaikExtendedModule module,
            byte[] hash)
    throws SignerException
    {
        if(publicKey instanceof ECPublicKey == false)
        {
            throw new SignerException("Operation CKM_ECDSA is not allowed for " + publicKey.getAlgorithm() + " public key");
        }

        IaikExtendedSlot slot = module.getSlot(slotId);
        if(slot == null)
        {
            throw new SignerException("Could not find slot " + slotId);
        }

        byte[] truncatedDigest = IoCertUtil.leftmost(hash, signatureKeyBitLength);

        byte[] signature = slot.CKM_ECDSA(truncatedDigest, keyId);
        return convertToX962Signature(signature);
    }

    public byte[] CKM_DSA(IaikExtendedModule module,
            byte[] hash)
    throws SignerException
    {
        if(publicKey instanceof DSAPublicKey == false)
        {
            throw new SignerException("Operation CKM_DSA is not allowed for " + publicKey.getAlgorithm() + " public key");
        }

        IaikExtendedSlot slot = module.getSlot(slotId);
        if(slot == null)
        {
            throw new SignerException("Could not find slot " + slotId);
        }
        byte[] truncatedDigest = IoCertUtil.leftmost(hash, signatureKeyBitLength);
        byte[] signature = slot.CKM_DSA(truncatedDigest, keyId);
        return convertToX962Signature(signature);
    }

    private static byte[] convertToX962Signature(byte[] signature)
    throws SignerException
    {
        byte[] ba = new byte[signature.length/2];
        ASN1EncodableVector sigder = new ASN1EncodableVector();

        System.arraycopy(signature, 0, ba, 0, ba.length);
        sigder.add(new ASN1Integer(new BigInteger(1, ba)));

        System.arraycopy(signature, ba.length, ba, 0, ba.length);
        sigder.add(new ASN1Integer(new BigInteger(1, ba)));

        DERSequence seq = new DERSequence(sigder);
        try
        {
            return seq.getEncoded();
        } catch (IOException e)
        {
            throw new SignerException("IOException, message: " + e.getMessage(), e);
        }
    }

    @Override
    public int compareTo(IaikP11Identity o)
    {
        return keyId.compareTo(o.keyId);
    }

}
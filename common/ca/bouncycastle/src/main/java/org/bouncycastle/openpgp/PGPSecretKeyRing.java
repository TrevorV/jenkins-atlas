package org.bouncycastle.openpgp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.bouncycastle.bcpg.BCPGInputStream;
import org.bouncycastle.bcpg.PacketTags;
import org.bouncycastle.bcpg.PublicSubkeyPacket;
import org.bouncycastle.bcpg.SecretKeyPacket;
import org.bouncycastle.bcpg.SecretSubkeyPacket;
import org.bouncycastle.bcpg.TrustPacket;

/**
 * Class to hold a single master secret key and its subkeys.
 * <p>
 * Often PGP keyring files consist of multiple master keys, if you are trying to process
 * or construct one of these you should use the PGPSecretKeyRingCollection class.
 */
public class PGPSecretKeyRing
    extends PGPKeyRing
{    
    List keys;
    List extraPubKeys;

    PGPSecretKeyRing(List keys)
    {
        this(keys, new ArrayList());
    }

    private PGPSecretKeyRing(List keys, List extraPubKeys)
    {
        this.keys = keys;
        this.extraPubKeys = extraPubKeys;
    }

    public PGPSecretKeyRing(
        byte[]    encoding)
        throws IOException, PGPException
    {
        this(new ByteArrayInputStream(encoding));
    }
    
    public PGPSecretKeyRing(
        InputStream    in)
        throws IOException, PGPException
    {
        this.keys = new ArrayList();
        this.extraPubKeys = new ArrayList();

        BCPGInputStream pIn = wrap(in);

        int initialTag = pIn.nextPacketTag();
        if (initialTag != PacketTags.SECRET_KEY && initialTag != PacketTags.SECRET_SUBKEY)
        {
            throw new IOException(
                "secret key ring doesn't start with secret key tag: " +
                "tag 0x" + Integer.toHexString(initialTag));
        }

        SecretKeyPacket secret = (SecretKeyPacket)pIn.readPacket();

        //
        // ignore GPG comment packets if found.
        //
        while (pIn.nextPacketTag() == PacketTags.EXPERIMENTAL_2)
        {
            pIn.readPacket();
        }
        
        TrustPacket trust = readOptionalTrustPacket(pIn);

        // revocation and direct signatures
        List keySigs = readSignaturesAndTrust(pIn);

        List ids = new ArrayList();
        List idTrusts = new ArrayList();
        List idSigs = new ArrayList();
        readUserIDs(pIn, ids, idTrusts, idSigs);

        keys.add(new PGPSecretKey(secret, new PGPPublicKey(secret.getPublicKeyPacket(), trust, keySigs, ids, idTrusts, idSigs)));


        // Read subkeys
        while (pIn.nextPacketTag() == PacketTags.SECRET_SUBKEY
            || pIn.nextPacketTag() == PacketTags.PUBLIC_SUBKEY)
        {
            if (pIn.nextPacketTag() == PacketTags.SECRET_SUBKEY)
            {
                SecretSubkeyPacket    sub = (SecretSubkeyPacket)pIn.readPacket();

                //
                // ignore GPG comment packets if found.
                //
                while (pIn.nextPacketTag() == PacketTags.EXPERIMENTAL_2)
                {
                    pIn.readPacket();
                }

                TrustPacket subTrust = readOptionalTrustPacket(pIn);
                List        sigList = readSignaturesAndTrust(pIn);

                keys.add(new PGPSecretKey(sub, new PGPPublicKey(sub.getPublicKeyPacket(), subTrust, sigList)));
            }
            else
            {
                PublicSubkeyPacket sub = (PublicSubkeyPacket)pIn.readPacket();

                TrustPacket subTrust = readOptionalTrustPacket(pIn);
                List        sigList = readSignaturesAndTrust(pIn);

                extraPubKeys.add(new PGPPublicKey(sub, subTrust, sigList));
            }
        }
    }

    /**
     * Return the public key for the master key.
     * 
     * @return PGPPublicKey
     */
    public PGPPublicKey getPublicKey()
    {
        return ((PGPSecretKey)keys.get(0)).getPublicKey();
    }

    /**
     * Return the master private key.
     * 
     * @return PGPSecretKey
     */
    public PGPSecretKey getSecretKey()
    {
        return ((PGPSecretKey)keys.get(0));
    }
    
    /**
     * Return an iterator containing all the secret keys.
     * 
     * @return Iterator
     */
    public Iterator getSecretKeys()
    {
        return Collections.unmodifiableList(keys).iterator();
    }
    
    public PGPSecretKey getSecretKey(
        long        keyId)
    {    
        for (int i = 0; i != keys.size(); i++)
        {
            PGPSecretKey    k = (PGPSecretKey)keys.get(i);
            
            if (keyId == k.getKeyID())
            {
                return k;
            }
        }
    
        return null;
    }

    /**
     * Return an iterator of the public keys in the secret key ring that
     * have no matching private key. At the moment only personal certificate data
     * appears in this fashion.
     *
     * @return  iterator of unattached, or extra, public keys.
     */
    public Iterator getExtraPublicKeys()
    {
        return extraPubKeys.iterator();
    }

    public byte[] getEncoded() 
        throws IOException
    {
        ByteArrayOutputStream    bOut = new ByteArrayOutputStream();
        
        this.encode(bOut);
        
        return bOut.toByteArray();
    }
    
    public void encode(
        OutputStream    outStream) 
        throws IOException
    {
        for (int i = 0; i != keys.size(); i++)
        {
            PGPSecretKey    k = (PGPSecretKey)keys.get(i);
            
            k.encode(outStream);
        }
        for (int i = 0; i != extraPubKeys.size(); i++)
        {
            PGPPublicKey    k = (PGPPublicKey)extraPubKeys.get(i);

            k.encode(outStream);
        }
    }

    /**
     * Replace the public key set on the secret ring with the corresponding key off the public ring.
     *
     * @param secretRing secret ring to be changed.
     * @param publicRing public ring containing the new public key set.
     */
    public static PGPSecretKeyRing replacePublicKeys(PGPSecretKeyRing secretRing, PGPPublicKeyRing publicRing)
    {
        List newList = new ArrayList(secretRing.keys.size());

        for (Iterator it = secretRing.keys.iterator(); it.hasNext();)
        {
            PGPSecretKey sk = (PGPSecretKey)it.next();
            PGPPublicKey pk = publicRing.getPublicKey(sk.getKeyID());

            newList.add(PGPSecretKey.replacePublicKey(sk, pk));
        }

        return new PGPSecretKeyRing(newList);
    }

    /**
     * Return a copy of the passed in secret key ring, with the master key and sub keys encrypted
     * using a new password and the passed in algorithm.
     *
     * @param ring the PGPSecretKeyRing to be copied.
     * @param oldPassPhrase the current password for key.
     * @param newPassPhrase the new password for the key.
     * @param newEncAlgorithm the algorithm to be used for the encryption.
     * @param rand source of randomness.
     * @param provider name of the provider to use
     */
    public static PGPSecretKeyRing copyWithNewPassword(
        PGPSecretKeyRing ring,
        char[]           oldPassPhrase,
        char[]           newPassPhrase,
        int              newEncAlgorithm,
        SecureRandom     rand,
        String           provider)
        throws PGPException, NoSuchProviderException
    {
        return copyWithNewPassword(ring, oldPassPhrase, newPassPhrase, newEncAlgorithm, rand, PGPUtil.getProvider(provider));
    }

    /**
     * Return a copy of the passed in secret key ring, with the master key and sub keys encrypted
     * using a new password and the passed in algorithm.
     *
     * @param ring the PGPSecretKeyRing to be copied.
     * @param oldPassPhrase the current password for key.
     * @param newPassPhrase the new password for the key.
     * @param newEncAlgorithm the algorithm to be used for the encryption.
     * @param rand source of randomness.
     * @param provider provider to use
     */
    public static PGPSecretKeyRing copyWithNewPassword(
        PGPSecretKeyRing ring,
        char[]           oldPassPhrase,
        char[]           newPassPhrase,
        int              newEncAlgorithm,
        SecureRandom     rand,
        Provider         provider)
        throws PGPException
    {
        List newKeys = new ArrayList(ring.keys.size());

        for (Iterator keys = ring.getSecretKeys(); keys.hasNext();)
        {
            newKeys.add(PGPSecretKey.copyWithNewPassword((PGPSecretKey)keys.next(), oldPassPhrase, newPassPhrase, newEncAlgorithm, rand, provider));
        }

        return new PGPSecretKeyRing(newKeys, ring.extraPubKeys);
    }

    /**
     * Returns a new key ring with the secret key passed in either added or
     * replacing an existing one with the same key ID.
     * 
     * @param secRing the secret key ring to be modified.
     * @param secKey the secret key to be added.
     * @return a new secret key ring.
     */
    public static PGPSecretKeyRing insertSecretKey(
        PGPSecretKeyRing  secRing,
        PGPSecretKey      secKey)
    {
        List       keys = new ArrayList(secRing.keys);
        boolean    found = false;
        boolean    masterFound = false;
        
        for (int i = 0; i != keys.size();i++)
        {
            PGPSecretKey   key = (PGPSecretKey)keys.get(i);
            
            if (key.getKeyID() == secKey.getKeyID())
            {
                found = true;
                keys.set(i, secKey);
            }
            if (key.isMasterKey())
            {
                masterFound = true;
            }
        }

        if (!found)
        {
            if (secKey.isMasterKey())
            {
                if (masterFound)
                {
                    throw new IllegalArgumentException("cannot add a master key to a ring that already has one");
                }

                keys.add(0, secKey);
            }
            else
            {
                keys.add(secKey);
            }
        }
        
        return new PGPSecretKeyRing(keys, secRing.extraPubKeys);
    }
    
    /**
     * Returns a new key ring with the secret key passed in removed from the
     * key ring.
     * 
     * @param secRing the secret key ring to be modified.
     * @param secKey the secret key to be removed.
     * @return a new secret key ring, or null if secKey is not found.
     */
    public static PGPSecretKeyRing removeSecretKey(
        PGPSecretKeyRing  secRing,
        PGPSecretKey      secKey)
    {
        List       keys = new ArrayList(secRing.keys);
        boolean    found = false;
        
        for (int i = 0; i < keys.size();i++)
        {
            PGPSecretKey   key = (PGPSecretKey)keys.get(i);
            
            if (key.getKeyID() == secKey.getKeyID())
            {
                found = true;
                keys.remove(i);
            }
        }
        
        if (!found)
        {
            return null;
        }
        
        return new PGPSecretKeyRing(keys, secRing.extraPubKeys);
    }
}

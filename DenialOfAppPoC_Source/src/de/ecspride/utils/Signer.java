/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* This file is a modified version of
 * com.android.signapk.SignApk.java.  The primary changes include
 * addition of the signZip() method, removal of main(), and the updates to
 * generate a signature that is verifiable by the Android recovery
 * programs,
 * Source are not any longer zip files, but a file directory */
package de.ecspride.utils;



import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import sun.misc.BASE64Encoder;
import sun.security.pkcs.ContentInfo;
import sun.security.pkcs.PKCS7;
import sun.security.pkcs.SignerInfo;
import sun.security.x509.AlgorithmId;
import sun.security.x509.X500Name;
import sys.util.jar.Attributes;
import sys.util.jar.Manifest;
import android.content.Context;

	/**
	 * This is a modified copy of com.android.signapk.SignApk.java.  It provides an
	 * API to sign JAR files in
	 * a way compatible with the mincrypt verifier, using SHA1 and RSA keys.
	 * 
	 * @author Stephan Huber
	 */
	public class Signer {

		private static Context mContext;
		private static final String TAG = "Signer";
		  
	    //get public key from raw reference
		 private static X509Certificate readPublicKey(int publicKey) throws IOException, GeneralSecurityException {	
		    InputStream input = mContext.getResources().openRawResource(publicKey);
	        try {
	            CertificateFactory cf = CertificateFactory.getInstance("X.509");
	            return (X509Certificate) cf.generateCertificate(input);
	        } finally {
	            input.close();
	        }
	    }

	   //get public key from raw reference
		 private static PrivateKey readPrivateKey(int privateKey) throws IOException, GeneralSecurityException {
		   	InputStream input = mContext.getResources().openRawResource(privateKey);
	    	ByteArrayOutputStream os = new ByteArrayOutputStream();
	    	 try {
				
	    		 
				byte[] buffer = new byte[2048];
				int numRead = input.read(buffer);
				while (numRead != -1) {
					os.write(buffer, 0, numRead);
					numRead = input.read(buffer);
				}
	    		byte[] bytes = os.toByteArray();
	    		input.read(bytes);
	    		
	    		KeySpec spec = new PKCS8EncodedKeySpec(bytes);
	    		

	    		try {
	    			return KeyFactory.getInstance("RSA").generatePrivate(spec);
	    		} catch (InvalidKeySpecException ex) {
	    			return KeyFactory.getInstance("DSA").generatePrivate(spec);
	    		}
	    	} finally {
	    		input.close();
	    	}
	    }

	    // Add the SHA1 of every file to the manifest, creating it if necessary. 
	    private static Manifest addDigestsToManifest(String path) throws IOException, GeneralSecurityException {
	    	ArrayList<String> files = new ArrayList<String>();
			files = FileUtils.getDirList(path);

	        Manifest mf = new Manifest();
	        Attributes main = mf.getMainAttributes();
	        main.putValue("Manifest-Version", "1.0");
	        main.putValue("Created-By", "1.0 (Android SignApk)");
	        

	        BASE64Encoder base64 = new BASE64Encoder();
	        MessageDigest md = MessageDigest.getInstance("SHA1");
	        byte[] buffer = new byte[4096];
	      
	        // We sort the input entries by name, and add them to the
	        // output manifest in sorted order.  We expect that the output
	        // map will be deterministic.
	        Collections.sort(files);
	        

			for (String file : files) {
				InputStream is = new FileInputStream(new File(path + file));
				for (int i; (i = is.read(buffer)) != -1;) {
					md.update(buffer, 0, i);
					
				}
				
				is.close();
				Attributes attr = new Attributes();
				attr.putValue("SHA1-Digest", base64.encode(md.digest()));
				mf.getEntries().put(file, attr);
			}
			

		
			return mf;
			
		}

	    // Write a .SF file with a digest of the specified manifest. 
	    private static void writeSignatureFile(Manifest manifest, OutputStream out)
	            throws IOException, GeneralSecurityException {
	        Manifest sf = new Manifest();
	        Attributes main = sf.getMainAttributes();
	        main.putValue("Signature-Version", "1.0");
	        main.putValue("Created-By", "1.0 (Android SignApk)");

	        BASE64Encoder base64 = new BASE64Encoder();
	        MessageDigest md = MessageDigest.getInstance("SHA1");
	        PrintStream print = new PrintStream(
	                new DigestOutputStream(new ByteArrayOutputStream(), md),
	                true, "UTF-8");

	        // Digest of the entire manifest
	        manifest.write(print);
	        print.flush();
	        main.putValue("SHA1-Digest-Manifest", base64.encode(md.digest()));

	        Map<String, Attributes> entries = manifest.getEntries();
	        for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
	            // Digest of the manifest stanza for this entry.
	            print.print("Name: " + entry.getKey() + "\r\n");
	            for (Map.Entry<Object, Object> att : entry.getValue().entrySet()) {
	                print.print(att.getKey() + ": " + att.getValue() + "\r\n");
	            }
	            print.print("\r\n");
	            print.flush();

	            Attributes sfAttr = new Attributes();
	            sfAttr.putValue("SHA1-Digest", base64.encode(md.digest()));
	            sf.getEntries().put(entry.getKey(), sfAttr);
	        }

	        sf.write(out);
	    }
	    

	    // Write a .RSA file with a digital signature. 
	    private static void writeSignatureBlock(byte[] signatureBytes, X509Certificate publicKey, OutputStream out)
	            throws IOException, GeneralSecurityException   {
	    	SignerInfo signerInfo = new SignerInfo(
	                new X500Name(publicKey.getIssuerX500Principal().getName()),
	                publicKey.getSerialNumber(),
	                AlgorithmId.get("SHA1"),
	                AlgorithmId.get("RSA"),
	                signatureBytes);


	        PKCS7 pkcs7 = new PKCS7(
	                new AlgorithmId[] { AlgorithmId.get("SHA1") },
	                new ContentInfo(ContentInfo.DATA_OID, null),
	                new X509Certificate[] { publicKey },
	                new SignerInfo[] { signerInfo });

	        pkcs7.encodeSignedData(out);
	    }

	    /**
	     * signes files in a directory for creating a signed zip file, result are the MANIFEST.MF, CERT.SF
	     * and a CERT.RSA file in the working directory
	     * Compression must be done by another method    
	     * @param inputPath path containing files to sign
	     * @param privateKeyID R.raw reference containing private key file
	     * @param publicKeyID R.raw reference containing public key file
	     * @param context context of activity for getting R.raw references
	     * @throws IOException
	     * @throws GeneralSecurityException
	     */
	    public static void sign(String inputPath, int privateKeyID, int publicKeyID, Context context)  throws IOException, GeneralSecurityException  {
	    	
	    	mContext = context;
	   
	    	PrivateKey privateKey = readPrivateKey(privateKeyID);
	    	X509Certificate publicKey = readPublicKey(publicKeyID);
	    	
	    	OutputStream outmf = null;
	    	OutputStream outCertSf = null;
	    	OutputStream outCertRsa = null;
	        try {
	        	
	        
	        	//create META-INF directory
	        	File dir = new File(inputPath + "/META-INF/");
	        	dir.mkdir();
	           	// MANIFEST.MF
	        
	        	Manifest manifest = addDigestsToManifest(inputPath);
	        	outmf = new FileOutputStream(new File(inputPath + "/META-INF/", "MANIFEST.MF"));
	           	manifest.write(outmf);
	        	outmf.close();
	        	
	        	// CERT.SF
	              	
	            // Can't use default Signature on Android.  Although it generates a signature that can be verified by jarsigner,
	            // the recovery program appears to require a specific algorithm/mode/padding.  So we use the custom ZipSignature instead.
	        	// Signature signature = Signature.getInstance("SHA1withRSA"); 
	        	ZipSignature signature = new ZipSignature();
	        	signature.initSign(privateKey);
	        	outCertSf = new FileOutputStream(new File(inputPath + "/META-INF/", "CERT.SF"));
	        	
	        	
	        	ByteArrayOutputStream out = new ByteArrayOutputStream();
	        	writeSignatureFile(manifest, out);
	           	byte[] sfBytes = out.toByteArray();
	     
	        	outCertSf.write(sfBytes);
	        	outCertSf.close();
	        	
	        	signature.update(sfBytes);
	        	byte[] signatureBytes = signature.sign();
	        	
	            
	        	// CERT.RSA
	         
	        	outCertRsa = new FileOutputStream(new File(inputPath + "/META-INF/", "CERT.RSA"));
	        	writeSignatureBlock(signatureBytes, publicKey, outCertRsa);
	        	
	        	outCertRsa.close();
	      
	        }
	        finally {
	            try {
	            		            
	                if (outmf != null) outmf.close();
	                if (outCertSf != null) outCertSf.close();
	                if (outCertRsa != null) outCertRsa.close();
	            } catch (IOException e) {
	                e.printStackTrace();
	            } 
	            
	        }
	     

	    }
	      
	}

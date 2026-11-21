#!/usr/bin/env node

// Use node-fetch for API requests
import fetch from 'node-fetch';

// API Gateway URL inside the cluster
const API_BASE_URL = 'http://api-gateway.banking-app.svc.cluster.local';

async function runTests() {
  console.log('🚀 Starting Kubernetes E2E Tests...');
  console.log(`API Base URL: ${API_BASE_URL}`);

  // Generate unique username for each test run
  const timestamp = Date.now();
  const testUsername = `k8stest${timestamp}`;
  const testEmail = `k8stest${timestamp}@example.com`;

  try {
    // Test 1: Health Check
    console.log('\n📊 Test 1: Health Check');
    const healthResponse = await fetch(`${API_BASE_URL}/actuator/health`);
    const healthData = await healthResponse.json();
    console.log('✅ Health check response:', healthData);
    
    if (healthData.status !== 'UP') {
      throw new Error('Health check failed');
    }

    // Test 2: User Registration
    console.log('\n👤 Test 2: User Registration');
    const registerResponse = await fetch(`${API_BASE_URL}/api/v1/user-service/register`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username: testUsername,
        email: testEmail,
        password: 'testpass123'
      })
    });

    if (!registerResponse.ok) {
      const errorText = await registerResponse.text();
      console.log('❌ Registration failed:', errorText);
      throw new Error(`Registration failed: ${registerResponse.status} ${errorText}`);
    }

    const registerData = await registerResponse.json();
    console.log('✅ User registered successfully:', registerData);

    // Test 3: User Login
    console.log('\n🔐 Test 3: User Login');
    const loginResponse = await fetch(`${API_BASE_URL}/api/v1/user-service/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        usernameOrEmail: testUsername,
        password: 'testpass123'
      })
    });

    if (!loginResponse.ok) {
      const errorText = await loginResponse.text();
      console.log('❌ Login failed:', errorText);
      throw new Error(`Login failed: ${loginResponse.status} ${errorText}`);
    }

    const loginData = await loginResponse.json();
    console.log('✅ User logged in successfully');
    const authToken = loginData.token;

    // Test 4: Get User Profile
    console.log('\n📝 Test 4: Get User Profile');
    const profileResponse = await fetch(`${API_BASE_URL}/api/v1/user-service/profile`, {
      headers: {
        'Authorization': `Bearer ${authToken}`,
      }
    });

    if (!profileResponse.ok) {
      const errorText = await profileResponse.text();
      console.log('❌ Profile fetch failed:', errorText);
      throw new Error(`Profile fetch failed: ${profileResponse.status} ${errorText}`);
    }

    const profileData = await profileResponse.json();
    console.log('✅ User profile retrieved:', profileData);

    // Test 5: Create Account
    console.log('\n🏦 Test 5: Create Account');
    const accountResponse = await fetch(`${API_BASE_URL}/api/v1/accounts-service/`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${authToken}`,
      },
      body: JSON.stringify({
        accountType: 'CHECKING',
        initialBalance: 1000.00
      })
    });

    if (!accountResponse.ok) {
      const errorText = await accountResponse.text();
      console.log('❌ Account creation failed:', errorText);
      throw new Error(`Account creation failed: ${accountResponse.status} ${errorText}`);
    }

    const accountData = await accountResponse.json();
    console.log('✅ Account created successfully:', accountData);
    const accountId = accountData.id;

    // Test 6: Get Account Details
    console.log('\n💰 Test 6: Get Account Details');
    const accountDetailsResponse = await fetch(`${API_BASE_URL}/api/v1/accounts-service/${accountId}`, {
      headers: {
        'Authorization': `Bearer ${authToken}`,
      }
    });

    if (!accountDetailsResponse.ok) {
      const errorText = await accountDetailsResponse.text();
      console.log('❌ Account details fetch failed:', errorText);
      throw new Error(`Account details fetch failed: ${accountDetailsResponse.status} ${errorText}`);
    }

    const accountDetails = await accountDetailsResponse.json();
    console.log('✅ Account details retrieved:', accountDetails);

    // Test 7: Get Account Balance
    console.log('\n💵 Test 7: Get Account Balance');
    const balanceResponse = await fetch(`${API_BASE_URL}/api/v1/accounts-service/${accountId}/balance`, {
      headers: {
        'Authorization': `Bearer ${authToken}`,
      }
    });

    if (!balanceResponse.ok) {
      const errorText = await balanceResponse.text();
      console.log('❌ Balance fetch failed:', errorText);
      throw new Error(`Balance fetch failed: ${balanceResponse.status} ${errorText}`);
    }

    const balanceData = await balanceResponse.json();
    console.log('✅ Account balance retrieved:', balanceData);

    // Test 8: Get Transaction History
    console.log('\n📊 Test 8: Get Transaction History');
    const transactionsResponse = await fetch(`${API_BASE_URL}/api/v1/transactions-service/`, {
      headers: {
        'Authorization': `Bearer ${authToken}`,
      }
    });

    if (!transactionsResponse.ok) {
      const errorText = await transactionsResponse.text();
      console.log('❌ Transaction history fetch failed:', errorText);
      throw new Error(`Transaction history fetch failed: ${transactionsResponse.status} ${errorText}`);
    }

    const transactionsData = await transactionsResponse.json();
    console.log('✅ Transaction history retrieved:', transactionsData);

    console.log('\n🎉 All tests completed successfully!');
    console.log('\n📋 Test Summary:');
    console.log('  ✅ Health Check');
    console.log('  ✅ User Registration');
    console.log('  ✅ User Login');
    console.log('  ✅ Get User Profile');
    console.log('  ✅ Create Account');
    console.log('  ✅ Get Account Details');
    console.log('  ✅ Get Account Balance');
    console.log('  ✅ Get Transaction History');
    
    process.exit(0);

  } catch (error) {
    console.error('\n❌ Test failed:', error.message);
    process.exit(1);
  }
}

// Run the tests
runTests();
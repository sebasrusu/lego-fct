'use strict';

const fs = require('fs');
const path = require('path');
const { faker } = require('@faker-js/faker');

const IMAGES_DIR = path.join(__dirname, 'images');
const images = [];
const users = [];

function loadImages() {
    if (!fs.existsSync(IMAGES_DIR)) return;
    const files = fs.readdirSync(IMAGES_DIR).filter(f => f.match(/\.(jpe?g|png|gif|webp)$/i));
    for (const f of files) {
        try { images.push(fs.readFileSync(path.join(IMAGES_DIR, f))); } catch (e) { /* ignore */ }
    }
}
loadImages();

// upload image body
function uploadImageBody(requestParams, context, ee, next) {
    if (images.length > 0) {
        requestParams.body = images[Math.floor(Math.random() * images.length)];
    }
    return next();
}

// store returned image id
function processUploadReply(requestParams, response, context, ee, next) {
    if (response && response.body && response.body.length > 0) {
        // keep as raw string (Artillery capture uses regexp in YAML)
        // optional: persist
    }
    return next();
}

// select an image id for download (not used by load-data.yml but exported)
function selectImageToDownload(context, events, done) {
    return done();
}

// user generation
function genNewUser(context, events, done) {
    const first = faker.person.firstName();
    const last = faker.person.lastName();
    context.vars.uId = `${first.toLowerCase()}.${last.toLowerCase()}${Math.floor(Math.random() * 1000)}`;
    context.vars.uName = `${first} ${last}`;
    context.vars.uPwd = faker.internet.password();
    return done();
}

function genNewUserReply(requestParams, response, context, ee, next) {
    try {
        if (response && response.statusCode >= 200 && response.statusCode < 300 && response.body) {
            const u = JSON.parse(response.body);
            users.push(u);
            fs.writeFileSync(path.join(process.cwd(), 'users.data'), JSON.stringify(users));
        }
    } catch (e) { /* ignore */ }
    return next();
}

// legoset generation
function genNewLegoSet(context, events, done) {
    context.vars.lsName = faker.commerce.productName();
    context.vars.lsDescription = faker.commerce.productDescription();
    return done();
}

// comment generation (simple)
function genProductComment(context, events, done) {
    context.vars.cmtText = `Nice set: ${context.vars.lsName || 'lego'}`;
    return done();
}

// auctions / bids
function genNewOldAuction(context, events, done) {
    context.vars.aucStartingPrice = Math.floor(Math.random() * 100) + 10;
    context.vars.aucLastBid = context.vars.aucStartingPrice - 1;
    const d = new Date(Date.now() + (24 * 60 * 60 * 1000) * (1 + Math.floor(Math.random() * 10)));
    context.vars.aucEndDate = d.toISOString();
    return done();
}

function genNewOldBid(context, events, done) {
    context.vars.aucLastBid = (context.vars.aucLastBid || 0) + 1 + Math.floor(Math.random() * 5);
    return done();
}

// select user (from created users)
function selectUser(context, events, done) {
    if (users.length > 0) {
        const u = users[Math.floor(Math.random() * users.length)];
        context.vars.uId = u.id;
        context.vars.uPwd = u.pwd;
    } else {
        delete context.vars.uId;
        delete context.vars.uPwd;
    }
    return done();
}

// loops used in YAML
function randomLoop90(context, next) {
    return next(Math.random() < 0.9);
}
function randomLoop70(context, next) {
    return next(Math.random() < 0.7);
}
function randomLoop95(context, next) {
    return next(Math.random() < 0.95);
}

module.exports = {
    uploadImageBody,
    processUploadReply,
    selectImageToDownload,
    genNewUser,
    genNewUserReply,
    genNewLegoSet,
    genProductComment,
    genNewOldAuction,
    genNewOldBid,
    selectUser,
    randomLoop90,
    randomLoop70,
    randomLoop95
};
package com.crunchbarcode.app

import android.app.Application
import com.crunchbarcode.app.data.repository.CrunchRepository

class CrunchApp : Application() {

    lateinit var repository: CrunchRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = CrunchRepository.getInstance(this)
    }
}
